mod common;

use std::fs;

use common::{assert_no_secret, fixture, import, read, relay};
use relayd::{Exit, Relay, render};
use serde_json::{Value, json};

fn json_fixture(name: &str) -> Value {
    serde_json::from_str(&read(name)).unwrap()
}

fn import_fixture(name: &str) -> Relay {
    import(
        &fixture(&format!("{name}.json")),
        &fixture(&format!("{name}.devices.json")),
    )
    .unwrap()
}

fn import_edited(
    edit_config: impl FnOnce(&mut Value),
    edit_devices: impl FnOnce(&mut Value),
) -> String {
    let dir = tempfile::tempdir().unwrap();
    let mut config = json_fixture("relay-direct.json");
    let mut devices = json_fixture("relay-direct.devices.json");
    edit_config(&mut config);
    edit_devices(&mut devices);
    let (config_path, devices_path) = (
        dir.path().join("config.json"),
        dir.path().join("devices.json"),
    );
    fs::write(&config_path, config.to_string()).unwrap();
    fs::write(&devices_path, devices.to_string()).unwrap();

    let error = import(&config_path, &devices_path).unwrap_err().to_string();
    assert_no_secret(&error);
    error
}

fn short_ids(config: &mut Value) -> &mut Value {
    &mut config["inbounds"][0]["streamSettings"]["realitySettings"]["shortIds"]
}

#[test]
fn the_user_shaped_relay_imports_to_the_golden_state() {
    assert_eq!(import_fixture("relay-direct"), relay("relay-direct"));
}

#[test]
fn a_wireguard_exit_imports_to_the_golden_state() {
    assert_eq!(import_fixture("relay-wireguard"), relay("relay-wireguard"));
}

#[test]
fn every_counter_survives_the_import() {
    let relay = import_fixture("relay-direct");

    let shared = &relay.state.devices[0];
    assert_eq!(
        (
            shared.up,
            shared.down,
            shared.added.as_str(),
            shared.seen.as_deref()
        ),
        (
            123_456_789,
            987_654_321,
            "2026-09-01T10:00:00Z",
            Some("2026-10-25T08:30:00Z")
        )
    );
    let laptop = &relay.state.devices[1];
    assert_eq!(
        (laptop.up, laptop.down, laptop.seen.as_deref()),
        (42, 4200, None)
    );
}

#[test]
fn import_then_render_reproduces_each_config() {
    for name in ["today", "relay-direct", "relay-wireguard"] {
        let config = json_fixture(&format!("{name}.json"));
        assert_eq!(render(&import_fixture(name)), config, "{name}");
    }
}

#[test]
fn a_relay_serving_only_stats_imports_and_renders_with_the_handler_service() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = json_fixture("relay-direct.json");
    config["api"]["services"] = json!(["StatsService"]);
    let path = dir.path().join("config.json");
    fs::write(&path, config.to_string()).unwrap();

    let relay = import(&path, &fixture("relay-direct.devices.json")).unwrap();

    assert_eq!(
        render(&relay)["api"]["services"],
        json!(["HandlerService", "StatsService"])
    );
}

#[test]
fn a_relay_from_before_the_stats_api_imports_without_one() {
    let relay = import_fixture("today");

    assert_eq!(relay.state.api, None);
    assert_eq!(relay.state.exit, Exit::Direct);
    assert_eq!(
        (relay.state.devices[0].up, relay.state.devices[0].down),
        (0, 0)
    );
}

#[test]
fn short_ids_in_another_order_still_import() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = json_fixture("relay-direct.json");
    short_ids(&mut config).as_array_mut().unwrap().reverse();
    let path = dir.path().join("config.json");
    fs::write(&path, config.to_string()).unwrap();

    let relay = import(&path, &fixture("relay-direct.devices.json")).unwrap();

    assert_eq!(
        relay.state.devices,
        self::relay("relay-direct").state.devices
    );
}

#[test]
fn a_short_id_no_device_holds_is_kept_as_a_spare() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = json_fixture("relay-direct.json");
    short_ids(&mut config)
        .as_array_mut()
        .unwrap()
        .push(json!("99887766"));
    let path = dir.path().join("config.json");
    fs::write(&path, config.to_string()).unwrap();

    let relay = import(&path, &fixture("relay-direct.devices.json")).unwrap();

    let spares: Vec<&str> = relay
        .state
        .spare_short_ids
        .iter()
        .map(|id| id.expose())
        .collect();
    assert_eq!(spares, ["99887766"]);
    assert_eq!(render(&relay), config);
}

#[test]
fn a_client_missing_from_devices_json_is_refused() {
    let error = import_edited(
        |config| {
            config["inbounds"][0]["settings"]["clients"]
                .as_array_mut()
                .unwrap()
                .push(json!({
                    "id": "55555555-5555-4555-8555-555555555555",
                    "flow": "xtls-rprx-vision",
                    "email": "stranger",
                }))
        },
        |_| {},
    );

    assert!(
        error.contains("clients not in devices.json (stranger); run: devices.sh adopt NAME"),
        "{error}"
    );
}

#[test]
fn a_relay_without_devices_json_is_refused() {
    let dir = tempfile::tempdir().unwrap();

    let error = import(&fixture("today.json"), &dir.path().join("devices.json"))
        .unwrap_err()
        .to_string();

    assert!(
        error.contains("clients not in devices.json (client1)"),
        "{error}"
    );
}

#[test]
fn an_empty_short_id_list_is_refused() {
    let error = import_edited(|config| *short_ids(config) = json!([]), |_| {});

    assert!(error.contains("short IDs missing or empty"), "{error}");
}

#[test]
fn an_empty_short_id_is_refused() {
    let error = import_edited(
        |config| short_ids(config).as_array_mut().unwrap().push(json!("")),
        |_| {},
    );

    assert!(error.contains("short IDs missing or empty"), "{error}");
}

#[test]
fn a_tracked_device_with_no_client_is_refused() {
    let error = import_edited(
        |_| {},
        |devices| {
            devices["gone"] =
                json!({ "shortId": "1234567890abcdef", "added": "2026-01-01T00:00:00Z" })
        },
    );

    assert!(
        error.contains("devices with no client in the config (gone)"),
        "{error}"
    );
}

#[test]
fn a_tracked_short_id_the_config_does_not_offer_is_refused() {
    let error = import_edited(
        |_| {},
        |devices| devices["laptop"]["shortId"] = json!("abcdef0123456789"),
    );

    assert!(
        error.contains("the short ID devices.json records for laptop is not in the config"),
        "{error}"
    );
}

#[test]
fn an_unknown_devices_json_field_is_refused() {
    let error = import_edited(|_| {}, |devices| devices["laptop"]["role"] = json!("admin"));

    assert!(error.contains("invalid value at line"), "{error}");
}

#[test]
fn a_routing_rule_relayd_does_not_model_is_refused() {
    let error = import_edited(
        |config| {
            config["routing"]["rules"].as_array_mut().unwrap().insert(
                0,
                json!({ "type": "field", "domain": ["geosite:cn"], "outboundTag": "block" }),
            )
        },
        |_| {},
    );

    assert!(
        error.contains(
            "/routing/rules is not what relayd renders; importing would change or drop it"
        ),
        "{error}"
    );
}

#[test]
fn a_setting_relayd_does_not_model_is_refused_by_its_path() {
    let error = import_edited(|config| config["log"]["loglevel"] = json!("debug"), |_| {});

    assert!(
        error.contains("/log/loglevel is not what relayd renders"),
        "{error}"
    );
}

#[test]
fn a_client_flow_other_than_vision_is_refused() {
    let error = import_edited(
        |config| config["inbounds"][0]["settings"]["clients"][1]["flow"] = json!(""),
        |_| {},
    );

    assert!(
        error.contains("/inbounds/0/settings/clients/1/flow is not what relayd renders"),
        "{error}"
    );
}

#[test]
fn a_config_without_the_reality_inbound_is_refused() {
    let error = import_edited(
        |config| config["inbounds"][0]["tag"] = json!("other"),
        |_| {},
    );

    assert!(error.contains("no vless-reality-443 inbound"), "{error}");
}

#[test]
fn a_client_listed_twice_is_refused() {
    let error = import_edited(
        |config| {
            let clients = config["inbounds"][0]["settings"]["clients"]
                .as_array_mut()
                .unwrap();
            clients.push(clients[1].clone());
        },
        |_| {},
    );

    assert!(
        error.contains("client laptop is listed twice in the config"),
        "{error}"
    );
}

#[test]
fn a_mistyped_short_id_list_is_refused_without_quoting_it() {
    let error = import_edited(
        |config| *short_ids(config) = json!("a1b2c3d4e5f60718"),
        |_| {},
    );

    assert!(
        error.contains("the vless-reality-443 entry: a missing, unknown or invalid value"),
        "{error}"
    );
}

#[test]
fn a_numeric_short_id_stays_out_of_the_error() {
    let error = import_edited(
        |_| {},
        |devices| devices["laptop"]["shortId"] = json!(987654321012_u64),
    );

    assert!(error.contains("invalid value at line"), "{error}");
    assert!(!error.contains("987654321012"), "{error}");
}

#[test]
fn a_value_with_an_escaped_quote_stays_out_of_the_error() {
    let error = import_edited(
        |_| {},
        |devices| devices["laptop"]["up"] = json!("s3cr\"et-value"),
    );

    assert!(!error.contains("s3cr"), "{error}");
    assert!(!error.contains("et-value"), "{error}");
}

#[test]
fn import_refuses_while_the_scripts_hold_the_lock() {
    let dir = tempfile::tempdir().unwrap();
    let lock = dir.path().join("devices.lock");
    let held = fs::File::create(&lock).unwrap();
    held.lock().unwrap();

    let error = relayd::import(
        &fixture("relay-direct.json"),
        &fixture("relay-direct.devices.json"),
        &lock,
    )
    .unwrap_err()
    .to_string();

    assert!(
        error.contains("held by another tool editing the relay"),
        "{error}"
    );
    drop(held);
    relayd::import(
        &fixture("relay-direct.json"),
        &fixture("relay-direct.devices.json"),
        &lock,
    )
    .unwrap();
}
