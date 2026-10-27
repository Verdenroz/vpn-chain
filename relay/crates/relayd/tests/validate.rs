mod common;

use common::{assert_no_secret, relay};
use relayd::{Api, Relay, Secret, XRAY_VERSION};

fn refusal(edit: impl FnOnce(&mut Relay)) -> String {
    let mut relay = relay("relay-direct");
    edit(&mut relay);
    let error = relay.validate().unwrap_err().to_string();
    assert_no_secret(&error);
    error
}

#[test]
fn the_fixtures_are_valid() {
    relay("relay-direct").validate().unwrap();
    relay("relay-wireguard").validate().unwrap();
}

#[test]
fn missing_server_names_are_refused() {
    let error = refusal(|relay| relay.state.inbound.server_names.clear());

    assert!(
        error.contains("server names are missing, empty or listed twice"),
        "{error}"
    );
}

#[test]
fn an_empty_server_name_is_refused() {
    let error = refusal(|relay| relay.state.inbound.server_names.push(String::new()));

    assert!(
        error.contains("server names are missing, empty or listed twice"),
        "{error}"
    );
}

#[test]
fn a_server_name_listed_twice_is_refused() {
    let error = refusal(|relay| {
        let names = &mut relay.state.inbound.server_names;
        names.push(names[0].clone());
    });

    assert!(
        error.contains("server names are missing, empty or listed twice"),
        "{error}"
    );
}

#[test]
fn a_dest_without_a_port_is_refused() {
    let error = refusal(|relay| relay.state.inbound.dest = "www.samsung.com".to_owned());

    assert!(
        error.contains("dest www.samsung.com is not a host:port"),
        "{error}"
    );
}

#[test]
fn a_dest_with_port_zero_or_out_of_range_is_refused() {
    for dest in ["www.samsung.com:0", "www.samsung.com:65536", ":443"] {
        let error = refusal(|relay| relay.state.inbound.dest = dest.to_owned());
        assert!(error.contains("is not a host:port"), "{dest}: {error}");
    }
}

#[test]
fn a_loopback_dest_is_refused() {
    for dest in ["127.0.0.1:443", "localhost:443", "[::1]:443"] {
        let error = refusal(|relay| relay.state.inbound.dest = dest.to_owned());
        assert!(error.contains("off this machine"), "{dest}: {error}");
    }
}

#[test]
fn inbound_port_zero_is_refused() {
    let error = refusal(|relay| relay.state.inbound.port = 0);

    assert!(
        error.contains("the inbound port must be 1 to 65535"),
        "{error}"
    );
}

#[test]
fn a_stats_api_on_the_inbound_port_or_port_zero_is_refused() {
    for port in [443, 0] {
        let error = refusal(|relay| relay.state.api = Some(Api { port }));
        assert!(error.contains("stats API port"), "{port}: {error}");
    }
}

#[test]
fn a_uuid_xray_would_hash_is_refused() {
    for uuid in [
        "laptop-id",
        "33333333333343338333333333333333",
        "33333333-3333-4333-8333-33333333333g",
    ] {
        let error = refusal(|relay| relay.state.devices[1].uuid = Secret::new(uuid));
        assert!(
            error.contains("device laptop has no canonical UUID"),
            "{uuid}: {error}"
        );
    }
}

#[test]
fn a_uuid_shared_across_case_is_refused() {
    let error = refusal(|relay| {
        let upper = relay.state.devices[0].uuid.expose().to_ascii_uppercase();
        relay.state.devices[1].uuid = Secret::new(upper);
    });

    assert!(error.contains("shares its UUID"), "{error}");
}

#[test]
fn a_reality_key_that_is_not_32_bytes_of_base64url_is_refused() {
    for key in [
        "",
        "yHxKfB2n0Pq6Zr8sT3uV5wX7yA9bC1dE3fG5hI7jK0",
        "yHxKfB2n0Pq6Zr8sT3uV5wX7yA9bC1dE3fG5hI7jK0M=",
        "yHxKfB2n0Pq6Zr8sT3uV5wX7yA9bC1dE3fG5hI7jK+M",
    ] {
        let error = refusal(|relay| relay.secrets.reality_private_key = Secret::new(key));
        assert!(
            error.contains("REALITY private key is not 32 bytes"),
            "{key}: {error}"
        );
    }
}

#[test]
fn a_wireguard_key_that_is_not_32_bytes_of_base64_is_refused() {
    let mut relay = relay("relay-wireguard");
    relay.secrets.exit_secret_key = Some(Secret::new("c2hvcnQ="));

    let error = relay.validate().unwrap_err().to_string();

    assert!(
        error.contains("WireGuard exit key is not 32 bytes"),
        "{error}"
    );
}

#[test]
fn a_state_for_another_xray_is_refused() {
    let error = refusal(|relay| relay.state.xray.version = "26.10.21".to_owned());

    assert!(
        error.contains("the state is for xray 26.10.21, but this relayd renders for xray"),
        "{error}"
    );
}

#[test]
fn the_xray_version_matches_the_server_pin() {
    let pins = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../server/lib/pins.sh"
    ))
    .unwrap();

    let pinned = pins
        .lines()
        .find_map(|line| line.strip_prefix("XRAY_VERSION=v"))
        .unwrap();
    assert_eq!(pinned, XRAY_VERSION);
}
