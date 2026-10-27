mod common;

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use common::{SECRETS, assert_no_secret, fixture, read, relay};
use relayd::{AGENT_PORT, Exit, Relay, Secret, secrets_path};
use serde_json::Value;

fn scratch() -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("relay.json");
    (dir, path)
}

fn write_state(path: &Path, edit: impl FnOnce(&mut Value)) {
    let mut state: Value = serde_json::from_str(&read("relay-direct.state.json")).unwrap();
    edit(&mut state);
    fs::write(path, serde_json::to_string(&state).unwrap()).unwrap();
    fs::copy(fixture("relay-direct.secrets.json"), secrets_path(path)).unwrap();
}

fn load_error(path: &Path) -> String {
    Relay::load(path, &secrets_path(path))
        .unwrap_err()
        .to_string()
}

#[test]
fn the_user_shaped_state_loads_with_its_counters() {
    let relay = relay("relay-direct");

    let names: Vec<_> = relay
        .state
        .devices
        .iter()
        .map(|d| d.name.as_str())
        .collect();
    assert_eq!(names, ["shared", "laptop"]);
    assert_eq!(relay.state.devices[0].up, 123_456_789);
    assert_eq!(relay.state.devices[0].down, 987_654_321);
    assert_eq!(relay.state.devices[1].seen, None);
    assert_eq!(relay.state.exit, Exit::Direct);
}

#[test]
fn a_created_relay_reads_back_the_same_and_is_private() {
    let (_dir, path) = scratch();
    let relay = relay("relay-direct");

    relay.create(&path, &secrets_path(&path)).unwrap();

    assert_eq!(Relay::load(&path, &secrets_path(&path)).unwrap(), relay);
    for file in [&path, &secrets_path(&path)] {
        assert_eq!(
            fs::metadata(file).unwrap().permissions().mode() & 0o777,
            0o600
        );
    }
}

#[test]
fn the_state_file_holds_no_private_key() {
    let (_dir, path) = scratch();
    relay("relay-direct")
        .create(&path, &secrets_path(&path))
        .unwrap();

    let state = fs::read_to_string(&path).unwrap();
    assert!(!state.contains("yHxKfB2n0Pq6Zr8sT3uV5wX7yA9bC1dE3fG5hI7jK0M"));
}

#[test]
fn create_refuses_to_replace_an_existing_state() {
    let (_dir, path) = scratch();
    let relay = relay("relay-direct");
    fs::write(&path, "kept").unwrap();

    let error = relay.create(&path, &secrets_path(&path)).unwrap_err();

    assert!(
        error.to_string().contains("refusing to overwrite"),
        "{error}"
    );
    assert_eq!(fs::read_to_string(&path).unwrap(), "kept");
    assert!(!secrets_path(&path).exists());
}

#[test]
fn secrets_left_without_a_state_are_replaced() {
    let (_dir, path) = scratch();
    let relay = relay("relay-direct");
    fs::write(secrets_path(&path), "left by a crash").unwrap();

    relay.create(&path, &secrets_path(&path)).unwrap();

    assert_eq!(Relay::load(&path, &secrets_path(&path)).unwrap(), relay);
}

#[test]
fn an_unknown_schema_version_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| state["v"] = 3.into());

    assert!(load_error(&path).contains("written by schema v3; this relayd reads v2"));
}

#[test]
fn a_v1_state_migrates_with_the_default_agent_port_and_no_keys() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        let state = state.as_object_mut().unwrap();
        state.insert("v".to_owned(), 1.into());
        state.remove("agent");
        state.remove("keys");
    });

    let migrated = Relay::load(&path, &secrets_path(&path)).unwrap();

    assert_eq!(migrated, relay("relay-direct"));
    assert_eq!(migrated.state.agent.port, AGENT_PORT);
    assert!(migrated.state.keys.is_empty());
}

#[test]
fn a_v1_state_whose_port_is_the_default_agent_port_migrates_to_the_next_free_one() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        let state = state.as_object_mut().unwrap();
        state.insert("v".to_owned(), 1.into());
        state.remove("agent");
        state.remove("keys");
        state["api"]["port"] = AGENT_PORT.into();
    });

    let migrated = Relay::load(&path, &secrets_path(&path)).unwrap();

    assert_eq!(migrated.state.agent.port, AGENT_PORT + 1);
}

#[test]
fn a_saved_migrated_state_is_the_current_schema() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        let state = state.as_object_mut().unwrap();
        state.insert("v".to_owned(), 1.into());
        state.remove("agent");
        state.remove("keys");
    });

    Relay::load(&path, &secrets_path(&path))
        .unwrap()
        .save_state(&path)
        .unwrap();

    let saved: Value = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
    assert_eq!(saved["v"], 2);
}

#[test]
fn an_agent_port_that_collides_is_refused() {
    for port in [0, 443, 10085] {
        let (_dir, path) = scratch();
        write_state(&path, |state| state["agent"]["port"] = port.into());

        assert!(
            load_error(&path).contains("the agent port must be"),
            "{port}"
        );
    }
}

#[test]
fn a_file_without_a_version_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        state.as_object_mut().unwrap().remove("v");
    });

    assert!(load_error(&path).contains("not a relayd file"));
}

#[test]
fn an_unknown_field_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| state["inbound"]["listen"] = "0.0.0.0".into());

    assert!(load_error(&path).contains("a missing, unknown or invalid value at line"));
}

#[test]
fn a_short_id_held_twice_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        state["devices"][1]["shortId"] = state["devices"][0]["shortId"].clone();
    });

    let error = load_error(&path);
    assert!(error.contains("a short ID is listed twice"), "{error}");
    assert_no_secret(&error);
}

#[test]
fn a_mistyped_key_is_refused_without_quoting_it() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        state["devices"][0]["up"] = state["devices"][0]["uuid"].clone();
    });

    let error = load_error(&path);
    assert!(error.contains("invalid value at line"), "{error}");
    assert_no_secret(&error);
}

#[test]
fn an_inbound_the_server_scripts_cannot_find_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| state["inbound"]["tag"] = "vless".into());

    assert!(load_error(&path).contains("the inbound tag must be vless-reality-443"));
}

#[test]
fn an_empty_short_id_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| state["devices"][1]["shortId"] = "".into());

    assert!(load_error(&path).contains("a short ID is empty"));
}

#[test]
fn a_state_with_no_short_ids_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| state["devices"] = Value::Array(vec![]));

    assert!(load_error(&path).contains("no short IDs"));
}

#[test]
fn a_device_name_the_scripts_would_refuse_is_refused() {
    let (_dir, path) = scratch();
    write_state(&path, |state| {
        state["devices"][0]["name"] = "Shared Phone".into()
    });

    assert!(load_error(&path).contains("invalid device name: Shared Phone"));
}

#[test]
fn a_wireguard_exit_without_its_key_is_refused() {
    let mut relay = relay("relay-direct");
    relay.state.exit = Exit::WireGuard {
        address: vec!["10.2.0.2/32".to_owned()],
        mtu: 1280,
        peer_public_key: "peer".to_owned(),
        endpoint: "203.0.113.7:51820".to_owned(),
        dns: "10.2.0.1".to_owned(),
    };

    let error = relay.validate().unwrap_err().to_string();
    assert!(error.contains("no private key in secrets"), "{error}");
}

#[test]
fn debug_output_shows_no_credential() {
    let mut relay = relay("relay-direct");
    relay.secrets.exit_secret_key = Some(Secret::new(SECRETS[2]));

    assert_no_secret(&format!("{relay:?}"));
    assert_no_secret(&format!("{relay:#?}"));
}
