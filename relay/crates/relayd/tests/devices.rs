mod common;

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use common::fakes::{FakeLive, FakeRestart};
use common::{fixture, key_for, read, signer};
use relay_jobs::{Jobs, Layout};
use relayd::{JobsRestart, Kind, Manager, Placement, Relay, Role, secrets_path, write_config};
use serde_json::Value;
use xray_api::Traffic;

const NOW: &str = "2026-10-27T09:00:00Z";
const SPARES: usize = 3;

struct Lab {
    dir: tempfile::TempDir,
    state: PathBuf,
    secrets: PathBuf,
    config: PathBuf,
    lock: PathBuf,
    live: FakeLive,
    restart: FakeRestart,
}

impl Lab {
    /// The `relay-direct` fixture with `spares` unissued short IDs, `laptop` an admin, and
    /// its config rendered where xray would read it.
    fn new(spares: usize) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let state = dir.path().join("relay.json");
        let lab = Self {
            secrets: secrets_path(&state),
            config: dir.path().join("config.json"),
            lock: dir.path().join("devices.lock"),
            state,
            dir,
            live: FakeLive::default(),
            restart: FakeRestart::default(),
        };
        let mut state: Value = serde_json::from_str(&read("relay-direct.state.json")).unwrap();
        state["devices"][1]["role"] = "admin".into();
        state["spareShortIds"] = (0..spares).map(|i| format!("{i:016x}")).collect();
        fs::write(&lab.state, state.to_string()).unwrap();
        fs::copy(fixture("relay-direct.secrets.json"), &lab.secrets).unwrap();
        let placement = Placement {
            group: "relayd-test-no-such-group",
            swap: None,
        };
        write_config(&lab.relay(), &lab.config, &placement).unwrap();
        lab
    }

    fn out(&self) -> PathBuf {
        self.dir.path().join("identity.json")
    }

    fn relay(&self) -> Relay {
        Relay::load(&self.state, &self.secrets).unwrap()
    }

    fn manager(&self) -> Manager<'_> {
        self.manager_with("true")
    }

    fn manager_with<'a>(&'a self, xray: &'a str) -> Manager<'a> {
        Manager {
            state: &self.state,
            secrets: &self.secrets,
            config: &self.config,
            lock: &self.lock,
            placement: Placement {
                group: "relayd-test-no-such-group",
                swap: Some(Path::new(xray)),
            },
            live: &self.live,
            restart: &self.restart,
            spares: SPARES,
            lock_wait: Duration::from_secs(5),
            now: || NOW.to_owned(),
        }
    }

    fn config_json(&self) -> Value {
        serde_json::from_str(&fs::read_to_string(&self.config).unwrap()).unwrap()
    }

    fn clients(&self) -> Vec<String> {
        self.config_json()["inbounds"][0]["settings"]["clients"]
            .as_array()
            .unwrap()
            .iter()
            .map(|client| client["email"].as_str().unwrap().to_owned())
            .collect()
    }

    fn short_ids(&self) -> Vec<String> {
        self.config_json()["inbounds"][0]["streamSettings"]["realitySettings"]["shortIds"]
            .as_array()
            .unwrap()
            .iter()
            .map(|id| id.as_str().unwrap().to_owned())
            .collect()
    }

    fn names(&self) -> Vec<String> {
        self.relay()
            .state
            .devices
            .iter()
            .map(|device| device.name.clone())
            .collect()
    }

    fn add_key(&self, device: &str, seed: u8) {
        let mut relay = self.relay();
        relay
            .state
            .keys
            .push(key_for(device, Role::Admin, &signer(seed)));
        relay.save_state(&self.state).unwrap();
    }

    fn identity(&self) -> Value {
        serde_json::from_str(&fs::read_to_string(self.out()).unwrap()).unwrap()
    }
}

fn timeout() -> xray_api::Error {
    xray_api::Error::Timeout(std::time::Duration::from_secs(1))
}

fn unavailable() -> xray_api::Error {
    xray_api::Error::Unavailable("refused".to_owned())
}

#[test]
fn add_issues_a_pooled_short_id_through_the_api_without_a_restart() {
    let lab = Lab::new(SPARES);

    lab.manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap();

    let relay = lab.relay();
    let phone = relay.state.devices.last().unwrap();
    assert_eq!(phone.name, "phone");
    assert_eq!(phone.role, Role::Member);
    assert_eq!(phone.added, NOW);
    assert_eq!(phone.short_id.expose(), format!("{:016x}", 0));
    assert_eq!(relay.state.spare_short_ids.len(), SPARES - 1);
    assert_eq!(lab.live.calls(), ["drain", "add phone"]);
    assert_eq!(lab.restart.count(), 0);
    assert_eq!(lab.clients(), ["shared", "laptop", "phone"]);
    assert_eq!(lab.short_ids().len(), 2 + SPARES);
}

#[test]
fn add_writes_the_identity_privately_and_keeps_it_out_of_errors() {
    let lab = Lab::new(SPARES);

    lab.manager()
        .add("phone", Role::Admin, &lab.out(), false)
        .unwrap();

    let relay = lab.relay();
    let phone = relay.state.devices.last().unwrap();
    let identity = lab.identity();
    assert_eq!(identity["uuid"], phone.uuid.expose());
    assert_eq!(identity["shortId"], phone.short_id.expose());
    assert_eq!(identity["name"], "phone");
    assert_eq!(identity["port"], 443);
    assert_eq!(identity["serverNames"][0], "www.samsung.com");
    let mode = fs::metadata(lab.out()).unwrap().permissions().mode();
    assert_eq!(mode & 0o777, 0o600);
    assert_eq!(phone.role, Role::Admin);
}

#[test]
fn add_will_not_overwrite_an_identity_file() {
    let lab = Lab::new(SPARES);
    fs::write(lab.out(), "mine").unwrap();

    let error = lab
        .manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap_err();

    assert!(error.to_string().contains("already exists"), "{error}");
    assert_eq!(fs::read_to_string(lab.out()).unwrap(), "mine");
    assert_eq!(lab.names(), ["shared", "laptop"]);
    assert!(!lab.live.calls().contains(&"add phone".to_owned()));
}

#[test]
fn add_refuses_a_bad_or_taken_name() {
    let lab = Lab::new(SPARES);

    for name in ["Phone", "", "laptop"] {
        let result = lab.manager().add(name, Role::Member, &lab.out(), false);
        assert!(result.is_err(), "{name}");
    }

    assert!(lab.live.calls().iter().all(|call| !call.starts_with("add")));
    assert_eq!(lab.names(), ["shared", "laptop"]);
}

#[test]
fn add_with_an_empty_pool_refuses_and_changes_nothing() {
    let lab = Lab::new(0);

    let error = lab
        .manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap_err();

    assert!(error.to_string().contains("--allow-restart"), "{error}");
    assert_eq!(lab.names(), ["shared", "laptop"]);
    assert_eq!(lab.restart.count(), 0);
    assert!(!lab.out().exists());
}

#[test]
fn add_with_an_empty_pool_and_allow_restart_refills_it_and_restarts_once() {
    let lab = Lab::new(0);

    lab.manager()
        .add("phone", Role::Member, &lab.out(), true)
        .unwrap();

    let relay = lab.relay();
    assert_eq!(relay.state.spare_short_ids.len(), SPARES);
    assert_eq!(lab.restart.count(), 1);
    assert_eq!(lab.live.calls(), ["drain"]);
    assert_eq!(lab.clients(), ["shared", "laptop", "phone"]);
    assert_eq!(
        lab.identity()["shortId"],
        relay.state.devices[2].short_id.expose()
    );
    assert!(
        lab.short_ids()
            .contains(&lab.identity()["shortId"].as_str().unwrap().to_owned())
    );
}

#[test]
fn counters_are_folded_and_saved_before_the_change() {
    let lab = Lab::new(SPARES);
    lab.live.traffic.lock().unwrap().extend([
        ("laptop".to_owned(), Traffic { up: 10, down: 20 }),
        ("ghost".to_owned(), Traffic { up: 1, down: 1 }),
    ]);
    lab.live.script_adds([Err(unavailable())]);

    lab.manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap_err();

    let relay = lab.relay();
    let laptop = &relay.state.devices[1];
    assert_eq!((laptop.up, laptop.down), (42 + 10, 4200 + 20));
    assert_eq!(laptop.seen.as_deref(), Some(NOW));
    let shared = &relay.state.devices[0];
    assert_eq!(shared.seen.as_deref(), Some("2026-10-25T08:30:00Z"));
}

#[test]
fn a_failed_stats_read_stops_the_change_before_it_starts() {
    let lab = Lab::new(SPARES);
    *lab.live.drain_fails.lock().unwrap() = true;

    let error = lab
        .manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap_err();

    assert!(error.to_string().contains("xray API"), "{error}");
    assert_eq!(lab.live.calls(), ["drain"]);
    assert_eq!(lab.names(), ["shared", "laptop"]);
    assert!(!lab.out().exists());
}

#[test]
fn an_api_failure_puts_the_state_and_config_back_and_removes_the_identity() {
    let lab = Lab::new(SPARES);
    let before = fs::read_to_string(&lab.config).unwrap();
    lab.live.script_adds([Err(unavailable())]);

    let error = lab
        .manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap_err();

    assert!(error.to_string().contains("unreachable"), "{error}");
    assert_eq!(lab.names(), ["shared", "laptop"]);
    assert_eq!(lab.relay().state.spare_short_ids.len(), SPARES);
    assert_eq!(fs::read_to_string(&lab.config).unwrap(), before);
    assert!(!lab.out().exists());
}

#[test]
fn a_rejected_config_changes_nothing_and_never_reaches_xray() {
    let lab = Lab::new(SPARES);
    let before = fs::read_to_string(&lab.config).unwrap();

    let error = lab
        .manager_with("false")
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap_err();

    assert!(error.to_string().contains("-test rejected"), "{error}");
    assert_eq!(lab.names(), ["shared", "laptop"]);
    assert_eq!(fs::read_to_string(&lab.config).unwrap(), before);
    assert_eq!(lab.live.calls(), ["drain"]);
}

#[test]
fn a_retried_add_that_finds_the_user_already_there_succeeds() {
    let lab = Lab::new(SPARES);
    lab.live.script_adds([
        Err(timeout()),
        Err(xray_api::Error::UserExists("phone".into())),
    ]);

    lab.manager()
        .add("phone", Role::Member, &lab.out(), false)
        .unwrap();

    assert_eq!(lab.live.calls(), ["drain", "add phone", "add phone"]);
    assert_eq!(lab.names(), ["shared", "laptop", "phone"]);
}

#[test]
fn a_first_add_that_finds_the_user_already_there_is_an_error() {
    let lab = Lab::new(SPARES);
    lab.live
        .script_adds([Err(xray_api::Error::UserExists("phone".into()))]);

    let result = lab.manager().add("phone", Role::Member, &lab.out(), false);

    assert!(result.is_err());
    assert_eq!(lab.names(), ["shared", "laptop"]);
}

#[test]
fn rename_swaps_the_user_under_its_own_uuid_and_keeps_its_totals() {
    let lab = Lab::new(SPARES);
    let uuid = lab.relay().state.devices[1].uuid.expose().to_owned();

    lab.manager().rename("laptop", "desk").unwrap();

    let relay = lab.relay();
    let desk = &relay.state.devices[1];
    assert_eq!(desk.name, "desk");
    assert_eq!(desk.uuid.expose(), uuid);
    assert_eq!((desk.up, desk.down), (42, 4200));
    assert_eq!(lab.live.calls(), ["drain", "remove laptop", "add desk"]);
    assert_eq!(lab.live.added_ids.lock().unwrap()[0].1, uuid);
    assert_eq!(lab.restart.count(), 0);
    assert_eq!(lab.clients(), ["shared", "desk"]);
    assert_eq!(relay.state.spare_short_ids.len(), SPARES);
}

#[test]
fn rename_refuses_a_missing_device_and_a_taken_name() {
    let lab = Lab::new(SPARES);

    assert!(lab.manager().rename("nobody", "desk").is_err());
    assert!(lab.manager().rename("laptop", "shared").is_err());
    assert!(lab.manager().rename("laptop", "Desk").is_err());

    assert_eq!(lab.live.calls().iter().filter(|c| *c != "drain").count(), 0);
}

#[test]
fn a_rename_that_cannot_add_the_new_name_puts_the_old_user_back() {
    let lab = Lab::new(SPARES);
    lab.live.script_adds([Err(unavailable())]);

    lab.manager().rename("laptop", "desk").unwrap_err();

    assert_eq!(
        lab.live.calls(),
        ["drain", "remove laptop", "add desk", "add laptop"]
    );
    assert_eq!(lab.names(), ["shared", "laptop"]);
    assert_eq!(lab.clients(), ["shared", "laptop"]);
}

#[test]
fn a_retried_remove_that_finds_the_user_gone_succeeds() {
    let lab = Lab::new(SPARES);
    lab.live.script_removes([
        Err(timeout()),
        Err(xray_api::Error::UserNotFound("laptop".into())),
    ]);

    lab.manager().rename("laptop", "desk").unwrap();

    assert_eq!(
        lab.live.calls(),
        ["drain", "remove laptop", "remove laptop", "add desk"]
    );
}

#[test]
fn rekey_swaps_in_a_new_uuid_and_pooled_short_id_and_retires_the_old_pair() {
    let lab = Lab::new(SPARES);
    let old = lab.relay().state.devices[1].clone();

    lab.manager().rekey("laptop", &lab.out()).unwrap();

    let relay = lab.relay();
    let laptop = &relay.state.devices[1];
    assert_ne!(laptop.uuid, old.uuid);
    assert_eq!(laptop.short_id.expose(), format!("{:016x}", 0));
    assert_eq!(
        (laptop.role, laptop.up, laptop.added.as_str()),
        (Role::Admin, 42, "2026-10-02T12:00:00Z")
    );
    assert_eq!(lab.live.calls(), ["drain", "remove laptop", "add laptop"]);
    assert_eq!(lab.restart.count(), 0);
    assert!(!lab.short_ids().contains(&old.short_id.expose().to_owned()));
    assert_eq!(lab.identity()["uuid"], laptop.uuid.expose());
    assert_eq!(lab.identity()["shortId"], laptop.short_id.expose());
    assert_eq!(relay.state.spare_short_ids.len(), SPARES - 1);
}

#[test]
fn rekey_with_an_empty_pool_refuses() {
    let lab = Lab::new(0);

    let error = lab.manager().rekey("laptop", &lab.out()).unwrap_err();

    assert!(error.to_string().contains("refill-pool"), "{error}");
    assert!(!lab.out().exists());
}

#[test]
fn a_failed_rekey_restores_the_old_user_and_removes_the_new_identity() {
    let lab = Lab::new(SPARES);
    let old = lab.relay().state.devices[1].clone();
    lab.live.script_adds([Err(unavailable())]);

    lab.manager().rekey("laptop", &lab.out()).unwrap_err();

    assert_eq!(lab.relay().state.devices[1], old);
    assert_eq!(
        lab.live.calls(),
        ["drain", "remove laptop", "add laptop", "add laptop"]
    );
    let ids = lab.live.added_ids.lock().unwrap();
    assert_eq!(ids[1].1, old.uuid.expose());
    assert!(!lab.out().exists());
}

#[test]
fn revoke_drops_the_uuid_and_short_id_refills_the_pool_and_restarts() {
    let lab = Lab::new(1);
    let shared = lab.relay().state.devices[0].clone();

    lab.manager().revoke("shared", false).unwrap();

    let relay = lab.relay();
    assert_eq!(lab.names(), ["laptop"]);
    assert_eq!(relay.state.spare_short_ids.len(), SPARES);
    assert_eq!(lab.restart.count(), 1);
    assert_eq!(lab.clients(), ["laptop"]);
    assert!(
        !lab.short_ids()
            .contains(&shared.short_id.expose().to_owned())
    );
    assert_eq!(lab.short_ids().len(), 1 + SPARES);
    assert_eq!(lab.live.calls(), ["drain"]);
}

#[test]
fn revoke_folds_the_counters_before_the_restart_discards_them() {
    let lab = Lab::new(SPARES);
    lab.live
        .traffic
        .lock()
        .unwrap()
        .insert("laptop".to_owned(), Traffic { up: 5, down: 6 });

    lab.manager().revoke("shared", false).unwrap();

    let laptop = &lab.relay().state.devices[0];
    assert_eq!((laptop.up, laptop.down), (47, 4206));
}

#[test]
fn revoke_goes_ahead_when_xray_cannot_be_read_and_reports_the_lost_counters() {
    let lab = Lab::new(SPARES);
    *lab.live.drain_fails.lock().unwrap() = true;

    let counters_lost = lab.manager().revoke("shared", false).unwrap();

    assert!(counters_lost);
    assert_eq!(lab.names(), ["laptop"]);
    assert_eq!(lab.clients(), ["laptop"]);
    assert_eq!(lab.restart.count(), 1);
    assert_eq!(lab.relay().state.devices[0].up, 42);
}

#[test]
fn revoke_with_a_readable_xray_loses_no_counters() {
    let lab = Lab::new(SPARES);

    assert!(!lab.manager().revoke("shared", false).unwrap());
}

#[test]
fn revoke_keeps_the_last_admin_unless_forced() {
    let lab = Lab::new(SPARES);

    let error = lab.manager().revoke("laptop", false).unwrap_err();

    assert!(error.to_string().contains("last admin"), "{error}");
    assert_eq!(lab.restart.count(), 0);
    assert_eq!(lab.names(), ["shared", "laptop"]);

    lab.manager().revoke("laptop", true).unwrap();
    assert_eq!(lab.names(), ["shared"]);
    assert_eq!(lab.restart.count(), 1);
}

#[test]
fn revoke_lets_an_admin_go_while_another_remains() {
    let lab = Lab::new(SPARES);
    lab.manager()
        .add("desk", Role::Admin, &lab.out(), false)
        .unwrap();

    lab.manager().revoke("laptop", false).unwrap();

    assert_eq!(lab.names(), ["shared", "desk"]);
}

#[test]
fn revoke_of_an_unknown_device_changes_nothing() {
    let lab = Lab::new(SPARES);

    assert!(lab.manager().revoke("nobody", false).is_err());

    assert_eq!(lab.restart.count(), 0);
    assert_eq!(lab.names(), ["shared", "laptop"]);
}

#[test]
fn a_failed_restart_is_reported_with_the_revocation_already_saved() {
    let lab = Lab::new(SPARES);
    *lab.restart.fails.lock().unwrap() = true;

    let error = lab.manager().revoke("shared", false).unwrap_err();

    assert!(error.to_string().contains("refill-pool"), "{error}");
    assert_eq!(lab.names(), ["laptop"]);
    assert_eq!(lab.clients(), ["laptop"]);
}

#[test]
fn a_failed_restart_after_an_add_keeps_the_identity_of_the_saved_device() {
    let lab = Lab::new(0);
    *lab.restart.fails.lock().unwrap() = true;

    lab.manager()
        .add("phone", Role::Member, &lab.out(), true)
        .unwrap_err();

    assert_eq!(lab.names(), ["shared", "laptop", "phone"]);
    assert_eq!(lab.identity()["name"], "phone");
}

#[test]
fn refill_pool_tops_up_and_restarts() {
    let lab = Lab::new(1);

    let added = lab.manager().refill_pool().unwrap();

    assert_eq!(added, SPARES - 1);
    assert_eq!(lab.relay().state.spare_short_ids.len(), SPARES);
    assert_eq!(lab.short_ids().len(), 2 + SPARES);
    assert_eq!(lab.restart.count(), 1);
}

#[test]
fn refill_pool_on_a_full_pool_still_restarts_so_a_failed_restart_can_be_finished() {
    let lab = Lab::new(SPARES);

    assert_eq!(lab.manager().refill_pool().unwrap(), 0);

    assert_eq!(lab.restart.count(), 1);
}

#[test]
fn every_operation_refuses_while_the_scripts_hold_the_lock() {
    let lab = Lab::new(SPARES);
    let lock = fs::File::create(&lab.lock).unwrap();
    lock.lock().unwrap();
    let manager = Manager {
        lock_wait: Duration::from_millis(100),
        ..lab.manager()
    };

    let results = [
        manager.add("phone", Role::Member, &lab.out(), false),
        manager.rename("laptop", "desk"),
        manager.rekey("laptop", &lab.out()),
        manager.revoke("shared", false).map(drop),
        manager.refill_pool().map(drop),
    ];

    for result in results {
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("held by another tool")
        );
    }
    assert!(lab.live.calls().is_empty());
    assert_eq!(lab.names(), ["shared", "laptop"]);
}

#[test]
fn issue_returns_the_identity_and_writes_no_file() {
    let lab = Lab::new(SPARES);

    let identity = lab
        .manager()
        .issue("phone", Role::Member, false, None)
        .unwrap();

    let relay = lab.relay();
    let phone = relay.state.devices.last().unwrap();
    let json = serde_json::to_value(&identity).unwrap();
    assert_eq!(json["uuid"], phone.uuid.expose());
    assert_eq!(json["shortId"], phone.short_id.expose());
    assert_eq!(json["name"], "phone");
    assert!(!lab.out().exists());
}

#[test]
fn reissue_returns_the_new_pair_and_writes_no_file() {
    let lab = Lab::new(SPARES);
    let old = lab.relay().state.devices[1].uuid.clone();

    let identity = lab.manager().reissue("laptop", None).unwrap();

    let json = serde_json::to_value(&identity).unwrap();
    assert_ne!(json["uuid"], old.expose());
    assert_eq!(json["uuid"], lab.relay().state.devices[1].uuid.expose());
    assert!(!lab.out().exists());
}

#[test]
fn a_refusal_says_which_kind_it_is() {
    let lab = Lab::new(SPARES);
    let manager = lab.manager();

    let kinds = [
        manager
            .issue("Phone", Role::Member, false, None)
            .unwrap_err(),
        manager
            .issue("laptop", Role::Member, false, None)
            .unwrap_err(),
        manager.rename("ghost", "spirit").unwrap_err(),
        manager.reissue("ghost", None).unwrap_err(),
    ]
    .map(|error| error.kind());

    assert_eq!(
        kinds,
        [Kind::Invalid, Kind::Conflict, Kind::Missing, Kind::Missing]
    );
}

#[test]
fn rename_moves_the_devices_management_keys() {
    let lab = Lab::new(SPARES);
    lab.add_key("laptop", 7);

    lab.manager().rename("laptop", "desk").unwrap();

    let relay = lab.relay();
    assert_eq!(relay.state.keys[0].device, "desk");
    relay.validate().unwrap();
}

#[test]
fn revoke_removes_the_devices_management_keys_and_keeps_the_rest() {
    let lab = Lab::new(SPARES);
    lab.add_key("shared", 7);
    lab.add_key("laptop", 8);

    lab.manager().revoke("shared", false).unwrap();

    let keys = lab.relay().state.keys;
    assert_eq!(keys.len(), 1);
    assert_eq!(keys[0].device, "laptop");
}

#[test]
fn a_restart_handed_to_the_runner_queues_a_job_instead_of_restarting() {
    let lab = Lab::new(SPARES);
    let jobs_dir = tempfile::tempdir().unwrap();
    for name in ["jobs", "requests", "confirm"] {
        fs::create_dir(jobs_dir.path().join(name)).unwrap();
    }
    let jobs = Jobs::new(Layout::new(jobs_dir.path()));
    let restart = JobsRestart::new(&jobs);
    let manager = Manager {
        restart: &restart,
        ..lab.manager()
    };

    manager.revoke("shared", false).unwrap();

    let id = restart.submitted().unwrap();
    assert_eq!(
        fs::read_to_string(jobs_dir.path().join(format!("requests/{id}.req"))).unwrap(),
        format!("restart-xray {id}\n")
    );
    assert_eq!(lab.restart.count(), 0);
    assert_eq!(lab.names(), ["laptop"]);
}
