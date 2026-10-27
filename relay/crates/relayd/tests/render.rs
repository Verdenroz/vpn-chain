mod common;

use std::fs;
use std::os::unix::fs::PermissionsExt;

use common::{read, relay};
use relayd::{Secret, render, write_config};
use serde_json::Value;

fn deployed(name: &str) -> Value {
    serde_json::from_str(&read(name)).unwrap()
}

#[test]
fn the_user_shaped_relay_renders_what_deploy_server_writes() {
    assert_eq!(
        render(&relay("relay-direct")),
        deployed("relay-direct.json")
    );
}

#[test]
fn a_wireguard_exit_renders_what_deploy_server_writes() {
    assert_eq!(
        render(&relay("relay-wireguard")),
        deployed("relay-wireguard.json")
    );
}

#[test]
fn a_relay_without_the_stats_api_renders_no_api_section() {
    let mut relay = relay("relay-direct");
    relay.state.api = None;

    let config = render(&relay);

    for absent in ["stats", "api", "policy"] {
        assert!(config.get(absent).is_none(), "{absent} rendered");
    }
    assert_eq!(config["inbounds"].as_array().unwrap().len(), 1);
    assert_eq!(config["routing"]["rules"][0]["outboundTag"], "block");
}

#[test]
fn spare_short_ids_follow_the_devices_ids() {
    let mut relay = relay("relay-direct");
    relay.state.spare_short_ids = vec![Secret::new("99887766")];

    let config = render(&relay);

    let ids = &config["inbounds"][0]["streamSettings"]["realitySettings"]["shortIds"];
    assert_eq!(ids[2], "99887766");
}

#[test]
fn the_written_config_replaces_the_old_one_byte_for_byte_as_jq_writes_it() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    fs::write(&path, "old").unwrap();

    write_config(&relay("relay-direct"), &path).unwrap();

    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        read("relay-direct.json")
    );
    assert_eq!(
        fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o644
    );
}
