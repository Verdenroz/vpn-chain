//! Device operations on a relay rendered by relayd and served by a real Xray v26.3.27 in
//! docker, with the sing-box clients the apps ship. Every config swap runs the real
//! `xray -test`. Needs docker with host networking: `cargo test --test live -- --ignored`.

mod site;

use std::path::Path;
use std::process::{Command, Stdio};
use std::time::Duration;

use live_harness::{ECHO_HOST, SING_BOX, Tunnel};
use relayd::{Error, Exit, Placement, Restart, Role, Secret, write_config};
use site::{LAPTOP, SPARES, Site};

#[test]
#[ignore = "needs docker"]
fn an_added_device_connects_while_xray_keeps_running() {
    let site = Site::start("add");
    let before = site.process();

    for version in SING_BOX {
        let name = format!("phone-{}", version.replace('.', "-"));
        let out = site.out(&name);
        site.manager()
            .add(&name, Role::Member, &out, false)
            .unwrap();

        let phone = site.client(&site.identity(&out), version);
        assert!(site.works(phone), "sing-box {version}");
    }

    assert_eq!(site.process(), before);
    assert!(site.works(site.client(&site.laptop, SING_BOX[0])));
    assert_eq!(site.users(), ["laptop", "phone-1-13-14", "phone-1-14-3"]);
}

#[test]
#[ignore = "needs docker"]
fn a_renamed_device_keeps_its_identity_and_xray_keeps_running() {
    let site = Site::start("rename");
    let before = site.process();

    site.manager().rename(LAPTOP, "desk").unwrap();

    assert_eq!(site.process(), before);
    assert_eq!(site.users(), ["desk"]);
    for version in SING_BOX {
        let proxy = site.client(&site.laptop, version);
        assert!(site.works(proxy), "sing-box {version}");
    }
    assert_eq!(site.relay().state.devices[0].name, "desk");
}

#[test]
#[ignore = "needs docker"]
fn a_rekeyed_device_refuses_its_old_identity_and_accepts_the_new_one() {
    let site = Site::start("rekey");
    let before = site.process();

    for version in SING_BOX {
        let out = site.out(&format!("laptop-{}", version.replace('.', "-")));
        let old = site.relay().state.devices[0].clone();
        let old = site::Credentials {
            uuid: old.uuid.expose().to_owned(),
            short_id: old.short_id.expose().to_owned(),
        };

        site.manager().rekey(LAPTOP, &out).unwrap();

        let new = site.identity(&out);
        assert_ne!(new.uuid, old.uuid);
        assert!(
            !site.works(site.client(&old, version)),
            "sing-box {version}"
        );
        assert!(site.works(site.client(&new, version)), "sing-box {version}");
        assert!(!site.config().contains(&old.short_id));
    }

    assert_eq!(site.process(), before);
}

#[test]
#[ignore = "needs docker"]
fn revoking_cuts_an_open_connection_and_drops_the_short_id_from_the_config() {
    let site = Site::start("revoke");
    for version in SING_BOX {
        let name = format!("phone-{}", version.replace('.', "-"));
        let out = site.out(&name);
        site.manager()
            .add(&name, Role::Member, &out, false)
            .unwrap();
        let phone = site.identity(&out);
        let proxy = site.client(&phone, version);
        let mut open = site
            .rt
            .block_on(Tunnel::open_to(proxy, ECHO_HOST, site.echo))
            .unwrap();
        site.rt.block_on(open.echo(b"before")).unwrap();
        let before = site.process();

        site.manager().revoke(&name, false).unwrap();

        assert_ne!(site.process(), before, "xray restarted");
        assert!(
            site.rt.block_on(open.echo(b"after")).is_err(),
            "sing-box {version}: the open connection survived"
        );
        assert!(!site.works(proxy), "sing-box {version}");
        assert!(!site.config().contains(&phone.short_id));
        assert!(!site.config().contains(&phone.uuid));
        assert!(site.works(site.client(&site.laptop, version)));
    }
}

#[test]
#[ignore = "needs docker"]
fn counters_and_api_added_devices_survive_the_restart() {
    let site = Site::start("restart");
    let phone_out = site.out("phone");
    let tablet_out = site.out("tablet");
    site.manager()
        .add("phone", Role::Member, &phone_out, false)
        .unwrap();
    site.manager()
        .add("tablet", Role::Member, &tablet_out, false)
        .unwrap();
    let phone = site.client(&site.identity(&phone_out), SING_BOX[0]);
    site.push(phone, 64 * 1024);

    site.manager().revoke("tablet", false).unwrap();

    let relay = site.relay();
    let held = relay.state.devices[1].clone();
    assert_eq!(held.name, "phone");
    assert!(held.up >= 64 * 1024 && held.down >= 64 * 1024, "{held:?}");
    assert!(held.seen.is_some());
    assert!(
        site.works(phone),
        "the API-added device survives the restart"
    );

    site.push(phone, 32 * 1024);
    site.manager().rename("phone", "mobile").unwrap();

    let grown = site.relay().state.devices[1].clone();
    assert!(grown.up >= held.up + 32 * 1024, "{held:?} then {grown:?}");
    assert!(
        grown.down >= held.down + 32 * 1024,
        "{held:?} then {grown:?}"
    );
}

#[test]
#[ignore = "needs docker"]
fn refilling_the_pool_restarts_xray_with_fresh_short_ids() {
    let site = Site::start("refill");
    let out = site.out("phone");
    site.manager()
        .add("phone", Role::Member, &out, false)
        .unwrap();
    let before = site.process();

    let added = site.manager().refill_pool().unwrap();

    assert_eq!(added, 1);
    assert_ne!(site.process(), before);
    assert_eq!(site.relay().state.spare_short_ids.len(), SPARES);
    assert!(site.works(site.client(&site.identity(&out), SING_BOX[0])));
}

#[test]
#[ignore = "needs docker"]
fn xray_test_rejects_a_config_before_it_replaces_the_live_one() {
    let site = Site::start("reject");
    let live = site.config();
    let before = site.process();
    let mut relay = site.relay();
    relay.state.exit = Exit::WireGuard {
        address: vec!["not an address".to_owned()],
        mtu: 1280,
        peer_public_key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".to_owned(),
        endpoint: "192.0.2.1:51820".to_owned(),
        dns: "192.0.2.53".to_owned(),
    };
    relay.secrets.exit_secret_key =
        Some(Secret::new("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    let placement = Placement {
        group: "relayd-test-no-such-group",
        swap: Some(&site.xray),
    };

    let error = write_config(&relay, &site.config, &placement).unwrap_err();

    assert!(error.to_string().contains("-test rejected"), "{error}");
    assert_eq!(site.config(), live);
    assert_eq!(site.process(), before);
    assert!(site.works(site.client(&site.laptop, SING_BOX[0])));
}

/// Runs the server script `devices.sh` streams over SSH while relayd holds the lock, from
/// inside the restart that every revocation ends with.
struct LegacyListDuringRestart<'a> {
    site: &'a Site,
    listed: std::cell::RefCell<Option<std::process::Child>>,
}

impl Restart for LegacyListDuringRestart<'_> {
    fn restart(&self) -> Result<(), Error> {
        let script = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../server/xray-clients.sh");
        let mut child = Command::new(script)
            .arg("list")
            .env("CFG", &self.site.config)
            .env("SIDECAR", self.site.dir.path().join("devices.json"))
            .env("LOCK", &self.site.lock)
            .env("XRAY", &self.site.xray)
            .env("STATS_CMD", "echo '{}'")
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        std::thread::sleep(Duration::from_millis(1500));
        assert!(
            child.try_wait().unwrap().is_none(),
            "the script ran while relayd held the lock"
        );
        *self.listed.borrow_mut() = Some(child);
        self.site.restart.restart()
    }
}

#[test]
#[ignore = "needs docker"]
fn the_legacy_script_waits_for_the_lock_relayd_holds() {
    let site = Site::start("lock");
    site.manager()
        .add("phone", Role::Member, &site.out("phone"), false)
        .unwrap();
    let legacy = LegacyListDuringRestart {
        site: &site,
        listed: Default::default(),
    };

    site.manager_restarting(&legacy)
        .revoke("phone", false)
        .unwrap();

    let child = legacy.listed.take().unwrap();
    let output = child.wait_with_output().unwrap();
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(stdout.contains(LAPTOP), "{stdout}");
    assert!(
        !stdout.contains("phone"),
        "the script read the config before relayd finished: {stdout}"
    );
}
