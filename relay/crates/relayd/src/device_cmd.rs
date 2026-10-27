use std::path::PathBuf;
use std::time::Duration;

use relayd::{
    DEVICES_LOCK, Device, Error, Manager, Placement, Relay, Role, SPARES, Systemctl, XrayLive, now,
    secrets_path,
};

use crate::args::Args;

const XRAY_GROUP: &str = "xray";
const LOCK_WAIT: Duration = Duration::from_secs(30);
const TARGET_FLAGS: [&str; 7] = [
    "state", "secrets", "config", "lock", "xray", "group", "spares",
];

/// Where the relay lives, from the flags every device command takes.
struct Target {
    state: PathBuf,
    secrets: PathBuf,
    config: PathBuf,
    lock: PathBuf,
    xray: PathBuf,
    group: String,
    spares: usize,
}

impl Target {
    fn from(args: &mut Args) -> Option<Self> {
        let state = args.path("state")?;
        let config = args.path("config")?;
        Some(Self {
            secrets: args.path("secrets").unwrap_or_else(|| secrets_path(&state)),
            config,
            lock: args
                .path("lock")
                .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK)),
            xray: args.path("xray").unwrap_or_else(|| PathBuf::from("xray")),
            group: args.value("group").unwrap_or_else(|| XRAY_GROUP.to_owned()),
            spares: match args.value("spares") {
                Some(spares) => spares.parse().ok()?,
                None => SPARES,
            },
            state,
        })
    }

    fn manage<T>(&self, operate: impl FnOnce(&Manager) -> Result<T, Error>) -> Result<T, Error> {
        let relay = Relay::load(&self.state, &self.secrets)?;
        let api = relay.state.api.as_ref().ok_or_else(|| {
            Error::new("this relay has no stats API, so its devices cannot be changed live")
        })?;
        let live = XrayLive::connect(api.port)?;
        operate(&Manager {
            state: &self.state,
            secrets: &self.secrets,
            config: &self.config,
            lock: &self.lock,
            placement: Placement {
                group: &self.group,
                swap: Some(&self.xray),
            },
            live: &live,
            restart: &Systemctl,
            spares: self.spares,
            lock_wait: LOCK_WAIT,
            now,
        })
    }
}

/// The flags every device command takes, and `extra` ones of its own.
fn valued(extra: &[&'static str]) -> Vec<&'static str> {
    [extra, &TARGET_FLAGS].concat()
}

pub fn device(args: &[&str]) -> Option<Result<(), Error>> {
    match args {
        ["list", rest @ ..] => list(rest),
        ["add", rest @ ..] => add(rest),
        ["rename", rest @ ..] => rename(rest),
        ["rekey", rest @ ..] => rekey(rest),
        ["revoke", rest @ ..] => revoke(rest),
        _ => None,
    }
}

pub fn maintenance(args: &[&str]) -> Option<Result<(), Error>> {
    let ["refill-pool", rest @ ..] = args else {
        return None;
    };
    let mut args = Args::parse(rest, &valued(&[]), &[])?;
    let [] = args.words()?;
    let target = Target::from(&mut args)?;
    let added = target.manage(|manager| manager.refill_pool());
    Some(added.map(|added| match added {
        0 => println!("the pool is full; nothing changed"),
        _ => println!("added {added} spare short IDs and restarted xray"),
    }))
}

fn list(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &["state", "secrets"], &[])?;
    let [] = args.words()?;
    let state = args.path("state")?;
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&state));
    Some(Relay::load(&state, &secrets).map(|relay| print_devices(&relay.state.devices)))
}

fn add(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &valued(&["role", "out"]), &["allow-restart"])?;
    let [name] = args.words()?;
    let name = name.to_owned();
    let role = match args.value("role").as_deref() {
        None | Some("member") => Role::Member,
        Some("admin") => Role::Admin,
        Some(_) => return None,
    };
    let out = args.path("out")?;
    let allow_restart = args.has("allow-restart");
    let target = Target::from(&mut args)?;
    let added = target.manage(|manager| manager.add(&name, role, &out, allow_restart));
    Some(added.map(|()| println!("added {name}; its identity is in {}", out.display())))
}

fn rename(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &valued(&[]), &[])?;
    let [old, new] = args.words()?;
    let (old, new) = (old.to_owned(), new.to_owned());
    let target = Target::from(&mut args)?;
    let renamed = target.manage(|manager| manager.rename(&old, &new));
    Some(renamed.map(|()| println!("renamed {old} to {new}")))
}

fn rekey(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &valued(&["out"]), &[])?;
    let [name] = args.words()?;
    let name = name.to_owned();
    let out = args.path("out")?;
    let target = Target::from(&mut args)?;
    let rekeyed = target.manage(|manager| manager.rekey(&name, &out));
    Some(rekeyed.map(|()| println!("rekeyed {name}; its new identity is in {}", out.display())))
}

fn revoke(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &valued(&[]), &["force"])?;
    let [name] = args.words()?;
    let name = name.to_owned();
    let force = args.has("force");
    let target = Target::from(&mut args)?;
    let revoked = target.manage(|manager| manager.revoke(&name, force));
    Some(revoked.map(|()| println!("revoked {name}; xray restarted, so open connections dropped")))
}

fn print_devices(devices: &[Device]) {
    println!(
        "{:<32} {:<7} {:<21} {:>10} {:>10}  SEEN",
        "NAME", "ROLE", "ADDED", "UP", "DOWN"
    );
    for device in devices {
        let role = match device.role {
            Role::Admin => "admin",
            Role::Member => "member",
        };
        println!(
            "{:<32} {:<7} {:<21} {:>10} {:>10}  {}",
            device.name,
            role,
            device.added,
            bytes(device.up),
            bytes(device.down),
            device.seen.as_deref().unwrap_or("-")
        );
    }
}

fn bytes(count: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut value = count as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    match unit {
        0 => format!("{count} B"),
        _ => format!("{value:.1} {}", UNITS[unit]),
    }
}
