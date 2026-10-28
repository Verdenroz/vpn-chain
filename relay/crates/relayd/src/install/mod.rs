//! `relayd install`: turns a Debian or Ubuntu machine into a relay, or adopts one that
//! `server/deploy-server.sh` made, and prints the one pairing link that connects the first
//! device.
//!
//! It is safe to run again: every step compares what is there first, and a run that finds a
//! finished install refreshes the binaries, the units and SSH hardening, and prints a new
//! pairing link while no device has enrolled.

mod ask;
pub(crate) mod entry;
mod host;
mod pinned;
mod ssh;
mod units;
pub(crate) mod wireguard;

use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use relay_jobs::{JobId, JobKind, JobState, Jobs, Layout};

use self::ask::{Asked, Needs};
use self::host::{
    give, give_all, have, output, put, require, run, run_reporting, say, systemctl, uid,
};
use crate::pairing::{self, Public, Purpose};
use crate::{
    AGENT_PORT, Agent, Api, DEVICES_LOCK, Device, Error, Exit, INBOUND_TAG, Inbound, Placement,
    Relay, Role, SCHEMA, SECRETS_SCHEMA, SPARES, Secret, Secrets, State, XRAY_VERSION, Xray,
    adopt_with, egress, layout, now, secrets_path, valid_name, write_config,
};

pub use pinned::RELEASES;

/// The borrowed site REALITY impersonates unless `--sni` names another.
const SNI: &str = "www.samsung.com";
const PORT: u16 = 443;

const FIRST_DEVICE: &str = "admin";
const API_PORT: u16 = 10085;
const BIN: &str = "/usr/local/bin";
const LIBEXEC: &str = "/usr/local/libexec/vpn-chain";
const PUBLIC_ADDRESS_URL: &str = "https://api.ipify.org";
const READY: Duration = Duration::from_secs(60);
const POLL: Duration = Duration::from_millis(250);
const XRAY_GROUP: &str = "xray";
const XRAY_LOGS: &str = "/var/log/xray";
const RELAYD: &str = "relayd";
const EGRESS_UNIT: &str = "vpn-chain-egress.service";

/// What the person asked for. Everything else is fixed.
#[derive(Clone, Default)]
pub struct Options {
    /// The first device on a fresh relay, or a new admin device on an adopted one.
    pub name: Option<String>,
    /// An existing device of an adopted relay to pair, made an admin.
    pub device: Option<String>,
    /// The site REALITY impersonates, [`SNI`] when absent.
    pub sni: Option<String>,
    /// The port xray listens on, [`PORT`] when absent.
    pub port: Option<u16>,
    /// The address the app dials; found from the internet when absent.
    pub host: Option<String>,
    /// A `wg-quick` file for a WireGuard exit.
    pub wireguard: Option<PathBuf>,
    /// A `wg-quick` file for the first device's WireGuard entry hop.
    pub entry: Option<PathBuf>,
}

struct Site {
    state: PathBuf,
    secrets: PathBuf,
    config: PathBuf,
    xray: PathBuf,
}

struct Created {
    device: Option<String>,
    restart: bool,
}

pub fn run_install(options: &Options) -> Result<(), Error> {
    check_host()?;
    check_options(options)?;
    let asked = ask::ask(options, needs(options)?)?;
    let options = &Options {
        name: options.name.clone().or_else(|| asked.name.clone()),
        ..options.clone()
    };
    require(&[("curl", "curl"), ("unzip", "unzip"), ("nft", "nftables")])?;
    let mut changed = units::install()?;
    units::accounts()?;
    changed |= binaries()?;
    let xray = pinned::ensure(Path::new(layout::XRAY_ROOT))?;
    let state = PathBuf::from(layout::STATE);
    let site = Site {
        secrets: secrets_path(&state),
        config: PathBuf::from(layout::XRAY_CONFIG),
        state,
        xray,
    };
    if site.state.exists() || site.config.exists() {
        refuse_new_relay_flags(options, &site)?;
    }
    if Public::beside(&site.state).exists() {
        return refresh(&site, changed, &asked);
    }
    let created = if site.state.exists() {
        Created {
            device: options.device.clone().or_else(|| options.name.clone()),
            restart: false,
        }
    } else {
        create(options, &site, &asked)?
    };
    start(&site, &created)?;
    save_public(&site, options)?;
    ssh::harden()?;
    say("done; run this command again for a new code until the first device is set up");
    print_first_code(&site, created.device.as_deref(), asked.entry.as_ref())
}

/// Prints the first admin device's pairing code, and for a relay that already had devices says
/// what happens to them. The entry hop, if any, goes to the device when it enrolls.
fn print_first_code(
    site: &Site,
    device: Option<&str>,
    entry: Option<&Secret>,
) -> Result<(), Error> {
    let link = pairing::issue_with_entry(
        &site.state,
        &site.secrets,
        Path::new(DEVICES_LOCK),
        device,
        Purpose::FirstAdmin,
        entry,
    )?;
    print!("{}", pairing::show(&link, Purpose::FirstAdmin));
    if Relay::load(&site.state, &site.secrets)?.state.devices.len() > 1 {
        say(
            "devices that already connect keep working; the admin can give each of them a code in the app (Settings → Devices → New code) to manage them there",
        );
    }
    Ok(())
}

fn check_host() -> Result<(), Error> {
    if output("id", &["-u"])?.trim() != "0" {
        return Err(Error::new("run as root"));
    }
    let release = fs::read_to_string("/etc/os-release")
        .map_err(|error| Error::at(Path::new("/etc/os-release"), error))?;
    let debian_like = release.lines().any(|line| {
        ["ID=", "ID_LIKE="].iter().any(|key| {
            line.strip_prefix(key).is_some_and(|value| {
                value
                    .trim_matches('"')
                    .split(' ')
                    .any(|id| id == "debian" || id == "ubuntu")
            })
        })
    });
    if !debian_like {
        return Err(Error::new("relayd install supports Debian and Ubuntu"));
    }
    if !Path::new("/run/systemd/system").exists() {
        return Err(Error::new("systemd is not running on this machine"));
    }
    Ok(())
}

/// Puts relayd and the job runner's two binaries where the units expect them, copied from
/// beside the relayd being run. Returns whether any changed.
fn binaries() -> Result<bool, Error> {
    let exe = std::env::current_exe().map_err(|error| Error::new(format!("relayd: {error}")))?;
    let from = exe.parent().expect("an executable has a directory");
    let mut changed = false;
    for (name, to) in [
        ("relayd", BIN),
        ("relay-jobs", BIN),
        ("relay-privileged", LIBEXEC),
    ] {
        let target = Path::new(to).join(name);
        match fs::read(from.join(name)) {
            Ok(bytes) => changed |= put(&target, &bytes, 0o755)?,
            Err(_) if target.exists() => {}
            Err(error) => return Err(Error::at(&from.join(name), error)),
        }
    }
    Ok(changed)
}

/// Brings an installed relay up to date with this relayd: its files, the egress rules and the
/// services. xray keeps running until it restarts, which loads a changed unit.
fn refresh(site: &Site, changed: bool, asked: &Asked) -> Result<(), Error> {
    if changed {
        units::reload()?;
    }
    let relay = Relay::load(&site.state, &site.secrets)?;
    let resolv = Path::new(egress::RESOLV_CONF);
    egress::apply(&egress::rules(relay.state.agent.port, resolv)?)?;
    systemctl(&[
        "enable",
        "--now",
        EGRESS_UNIT,
        "relay-privileged.path",
        "xray.service",
        "relayd.service",
    ])?;
    if changed {
        systemctl(&["restart", "relayd"])?;
        say("updated relayd and its units");
    }
    ssh::harden()?;
    if relay.state.keys.is_empty() {
        say("already installed; no device is set up yet, so here is a new code");
        print_first_code(site, None, asked.entry.as_ref())
    } else {
        say("already set up; add devices from the app (Settings → Devices)");
        Ok(())
    }
}

/// The questions this run has left, by what the machine already has. A name only matters for a
/// device that does not exist yet, and only a new relay takes an exit.
fn needs(options: &Options) -> Result<Needs, Error> {
    let state = Path::new(layout::STATE);
    let claimed = Public::beside(state).exists()
        && !Relay::load(state, &secrets_path(state))?
            .state
            .keys
            .is_empty();
    if claimed && options.entry.is_some() {
        return Err(Error::invalid(
            "--entry is for the first device, and this relay already has one",
        ));
    }
    Ok(Needs {
        name: !state.exists() && options.name.is_none() && options.device.is_none(),
        entry: !claimed,
        exit: !state.exists()
            && !Path::new(layout::XRAY_CONFIG).exists()
            && options.wireguard.is_none(),
    })
}

fn check_options(options: &Options) -> Result<(), Error> {
    if options.name.is_some() && options.device.is_some() {
        return Err(Error::invalid(
            "--name and --device cannot be used together",
        ));
    }
    for name in [&options.name, &options.device].into_iter().flatten() {
        if !valid_name(name) {
            return Err(Error::invalid(format!("invalid device name: {name}")));
        }
    }
    if let Some(sni) = options.sni.as_deref().filter(|sni| !valid_host_name(sni)) {
        return Err(Error::invalid(format!("{sni} is not a host name")));
    }
    Ok(())
}

/// `--sni`, `--port` and `--wireguard` shape a relay that does not exist yet. On one that does
/// they must say what it already is, or they would be ignored without a word.
fn refuse_new_relay_flags(options: &Options, site: &Site) -> Result<(), Error> {
    let same = if site.state.exists() {
        let inbound = Relay::load(&site.state, &site.secrets)?.state.inbound;
        options
            .sni
            .as_ref()
            .is_none_or(|sni| inbound.server_names.contains(sni))
            && options.port.is_none_or(|port| port == inbound.port)
    } else {
        options.sni.is_none() && options.port.is_none()
    };
    if options.wireguard.is_some() || !same {
        return Err(Error::invalid(
            "--sni, --port and --wireguard set up a new relay, and this machine already has one",
        ));
    }
    Ok(())
}

fn create(options: &Options, site: &Site, asked: &Asked) -> Result<Created, Error> {
    if site.config.exists() {
        adopt(options, site)
    } else if options.device.is_some() {
        Err(Error::invalid(
            "--device names a device of an existing relay, and there is none here",
        ))
    } else {
        fresh(options, site, asked)
    }
}

fn fresh(options: &Options, site: &Site, asked: &Asked) -> Result<Created, Error> {
    say("setting up a new relay");
    let (private, _) = pinned::keypair(&site.xray)?;
    let (exit, exit_secret_key) = match (&options.wireguard, &asked.exit) {
        (Some(path), _) => {
            let (exit, key) = wireguard::read(path)?;
            (exit, Some(key))
        }
        (None, Some((exit, key))) => (exit.clone(), Some(key.clone())),
        (None, None) => (Exit::Direct, None),
    };
    let name = options.name.as_deref().unwrap_or(FIRST_DEVICE);
    let sni = options.sni.as_deref().unwrap_or(SNI);
    let relay = Relay {
        state: State {
            v: SCHEMA,
            xray: Xray {
                version: XRAY_VERSION.to_owned(),
            },
            inbound: Inbound {
                tag: INBOUND_TAG.to_owned(),
                port: options.port.unwrap_or(PORT),
                server_names: vec![sni.to_owned()],
                dest: format!("{sni}:443"),
            },
            devices: vec![device(name, Role::Admin, crate::devices::new_short_id())],
            spare_short_ids: crate::devices::topped_up(Vec::new(), SPARES),
            exit,
            api: Some(Api { port: API_PORT }),
            agent: Agent { port: AGENT_PORT },
            keys: Vec::new(),
        },
        secrets: Secrets {
            v: SECRETS_SCHEMA,
            reality_private_key: private,
            exit_secret_key,
        },
    };
    let placement = Placement {
        group: XRAY_GROUP,
        swap: None,
    };
    let written = relay
        .create(&site.state, &site.secrets)
        .and_then(|()| write_config(&relay, &site.config, &placement))
        .and_then(|()| accepts(site, &site.config));
    if written.is_err() {
        for path in [&site.state, &site.secrets, &site.config] {
            let _ = fs::remove_file(path);
        }
    }
    written?;
    own(&site.config)?;
    Ok(Created {
        device: Some(name.to_owned()),
        restart: false,
    })
}

fn adopt(options: &Options, site: &Site) -> Result<Created, Error> {
    say(format!("adopting the relay in {}", site.config.display()));
    let name = options.name.as_deref().unwrap_or(FIRST_DEVICE).to_owned();
    let placement = Placement {
        group: XRAY_GROUP,
        swap: Some(&site.xray),
    };
    let existing = options.device.clone();
    let restart = adopt_with(
        &site.config,
        &site.config.with_file_name("devices.json"),
        &site.state,
        &site.secrets,
        Path::new(DEVICES_LOCK),
        &placement,
        |relay| {
            let relay = with_pool(relay);
            match &existing {
                Some(device) => relay.with_admin(device),
                None => with_admin_device(relay, &name),
            }
        },
    )?;
    own(&site.config)?;
    Ok(Created {
        device: existing.or(Some(name)),
        restart,
    })
}

fn with_pool(relay: Relay) -> Relay {
    let spare_short_ids = crate::devices::topped_up(relay.state.spare_short_ids.clone(), SPARES);
    Relay {
        state: State {
            spare_short_ids,
            ..relay.state
        },
        secrets: relay.secrets,
    }
}

fn with_admin_device(relay: Relay, name: &str) -> Result<Relay, Error> {
    if relay.state.devices.iter().any(|held| held.name == name) {
        return Err(Error::conflict(format!(
            "device {name} already exists; pair it with --device {name}"
        )));
    }
    let (short_id, spares) = relay
        .state
        .spare_short_ids
        .split_first()
        .expect("the pool was filled");
    let added = device(name, Role::Admin, short_id.clone());
    Ok(Relay {
        state: State {
            devices: relay.state.devices.iter().cloned().chain([added]).collect(),
            spare_short_ids: spares.to_vec(),
            ..relay.state
        },
        secrets: relay.secrets,
    })
}

fn device(name: &str, role: Role, short_id: crate::Secret) -> Device {
    Device {
        name: name.to_owned(),
        uuid: crate::devices::new_uuid(),
        short_id,
        role,
        added: now(),
        up: 0,
        down: 0,
        seen: None,
    }
}

fn accepts(site: &Site, config: &Path) -> Result<(), Error> {
    let xray = site.xray.to_string_lossy();
    let path = config.to_string_lossy();
    run_reporting(&xray, &["-test", "-config", &path])
}

/// Hands relayd its files, xray's group the config, which holds the REALITY private key, and
/// xray the logs. `xray -test` run as root or relayd opens its log, so the logs are group
/// writable for relayd, which is in xray's group.
pub fn own(config: &Path) -> Result<(), Error> {
    let (relayd, group, xray) = (uid(RELAYD)?, host::gid(RELAYD)?, host::gid(XRAY_GROUP)?);
    give_all(Path::new(layout::RELAY_DIR), relayd, group)?;
    give(config, relayd, xray)?;
    let logs = Path::new(XRAY_LOGS);
    give_all(logs, uid(XRAY_GROUP)?, xray)?;
    for entry in fs::read_dir(logs)
        .map_err(|error| Error::at(logs, error))?
        .flatten()
    {
        mode(&entry.path(), 0o664)?;
    }
    let dir = config.parent().expect("a config has a directory");
    for entry in fs::read_dir(dir)
        .map_err(|error| Error::at(dir, error))?
        .flatten()
    {
        if entry.file_name().to_string_lossy().contains(".bak.") {
            mode(&entry.path(), 0o600)?;
        }
    }
    Ok(())
}

fn mode(path: &Path, mode: u32) -> Result<(), Error> {
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
        .map_err(|error| Error::at(path, error))
}

fn start(site: &Site, created: &Created) -> Result<(), Error> {
    let relay = Relay::load(&site.state, &site.secrets)?;
    units::reload()?;
    network_tuning(relay.state.inbound.port);
    systemctl(&[
        "enable",
        "--now",
        "vpn-chain-egress.service",
        "relay-privileged.path",
    ])?;
    if created.restart {
        systemctl(&["enable", "xray.service"])?;
    } else {
        systemctl(&["enable", "--now", "xray.service"])?;
    }
    systemctl(&["enable", "--now", "relayd.service"])?;
    if created.restart {
        say(
            "restarting xray to load the new config; a session that runs through this relay will drop, so reconnect and run the setup command again for the code",
        );
        restart_detached(relay.state.inbound.port)?;
    }
    for port in [relay.state.inbound.port, relay.state.agent.port] {
        wait_for_port(port)?;
    }
    Ok(())
}

/// Best effort: BBR and the firewall help a relay but a host that lacks them still works.
fn network_tuning(port: u16) {
    let _ = run("modprobe", &["tcp_bbr"]);
    if run("sysctl", &["-p", "/etc/sysctl.d/99-vpn-chain-bbr.conf"]).is_err() {
        host::warn("could not turn on BBR; the relay works without it");
    }
    if have("ufw")
        && output("ufw", &["status"]).is_ok_and(|status| status.contains("Status: active"))
    {
        let _ = run("ufw", &["allow", &format!("{port}/tcp")]);
    }
}

/// Restarts xray as a job, so the restart outlives this process, and confirms it once the
/// relay answers again.
fn restart_detached(port: u16) -> Result<(), Error> {
    let jobs = Jobs::new(Layout::new(layout::JOBS_DIR));
    let failed = |error: relay_jobs::Error| Error::new(format!("restart job: {error}"));
    let id = jobs.submit(JobKind::RestartXray).map_err(failed)?;
    wait_for_job(&jobs, &id, |state| {
        state == JobState::AwaitingConfirm || state.is_terminal()
    })?;
    wait_for_port(port)?;
    jobs.confirm(&id).map_err(failed)
}

fn wait_for_job(jobs: &Jobs, id: &JobId, done: impl Fn(JobState) -> bool) -> Result<(), Error> {
    let deadline = Instant::now() + READY;
    loop {
        let state = jobs
            .status(id)
            .map_err(|error| Error::new(format!("restart job: {error}")))?
            .state;
        if state == JobState::Failed || state == JobState::RolledBack {
            return Err(Error::new("xray did not restart; the old config is back"));
        }
        if done(state) {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(Error::new("the xray restart did not finish in time"));
        }
        std::thread::sleep(POLL);
    }
}

fn wait_for_port(port: u16) -> Result<(), Error> {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let deadline = Instant::now() + READY;
    while TcpStream::connect_timeout(&address, POLL).is_err() {
        if Instant::now() >= deadline {
            return Err(Error::new(format!("nothing is listening on port {port}")));
        }
        std::thread::sleep(POLL);
    }
    Ok(())
}

/// Records where the app dials and the REALITY public key, for `relayd pair` to use later.
fn save_public(site: &Site, options: &Options) -> Result<(), Error> {
    let relay = Relay::load(&site.state, &site.secrets)?;
    let key = pinned::public_key(&site.xray, &relay.secrets.reality_private_key)?;
    let path = Public::beside(&site.state);
    Public::new(&public_host(options)?, &key).create(&path)?;
    give(&path, uid(RELAYD)?, host::gid(RELAYD)?)
}

fn public_host(options: &Options) -> Result<String, Error> {
    let host = match &options.host {
        Some(host) => host.clone(),
        None => output("curl", &["-fsS", "--max-time", "10", PUBLIC_ADDRESS_URL])
            .map_err(|_| Error::new("cannot find this machine's public address; pass --host"))?
            .trim()
            .to_owned(),
    };
    if host.parse::<IpAddr>().is_ok() || valid_host_name(&host) {
        Ok(host)
    } else {
        Err(Error::invalid(format!(
            "{host} is not an address or a host name"
        )))
    }
}

fn valid_host_name(name: &str) -> bool {
    name.len() <= 253
        && name.split('.').all(|label| {
            (1..=63).contains(&label.len())
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_names_follow_the_dns_label_rules() {
        for good in [
            "www.samsung.com",
            "dest.test",
            "a-b.example.org",
            "localhost",
        ] {
            assert!(valid_host_name(good), "{good}");
        }
        let long_label = format!("{}.com", "a".repeat(64));
        for bad in [
            "",
            "-bad.example",
            "bad-.example",
            "a..b",
            "a b.example",
            &long_label,
        ] {
            assert!(!valid_host_name(bad), "{bad}");
        }
    }
}
