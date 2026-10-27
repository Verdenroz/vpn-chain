mod common;

use common::{read, relay};
use relayd::{Secret, render};
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
    assert_eq!(config["routing"]["rules"][1]["outboundTag"], "block");
}

#[test]
fn the_agent_route_precedes_the_private_address_block() {
    let config = render(&relay("relay-direct"));

    let tags: Vec<&Value> = config["routing"]["rules"]
        .as_array()
        .unwrap()
        .iter()
        .map(|rule| &rule["outboundTag"])
        .collect();
    assert_eq!(tags, ["api", "agent", "block", "direct"]);
    assert_eq!(
        config["routing"]["rules"][1]["domain"],
        serde_json::json!(["full:agent.vpn-chain.invalid"])
    );
}

#[test]
fn the_agent_outbound_redirects_to_its_loopback_port_only() {
    let mut relay = relay("relay-wireguard");
    relay.state.agent.port = 12345;

    let config = render(&relay);

    let agent = config["outbounds"]
        .as_array()
        .unwrap()
        .iter()
        .find(|outbound| outbound["tag"] == "agent")
        .unwrap();
    assert_eq!(agent["protocol"], "freedom");
    assert_eq!(agent["settings"]["redirect"], "127.0.0.1:12345");
}

#[test]
fn spare_short_ids_follow_the_devices_ids() {
    let mut relay = relay("relay-direct");
    relay.state.spare_short_ids = vec![Secret::new("99887766")];

    let config = render(&relay);

    let ids = &config["inbounds"][0]["streamSettings"]["realitySettings"]["shortIds"];
    assert_eq!(ids[2], "99887766");
}
