mod common;

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

use common::{assert_no_secret, fixture, read};

fn relayd(args: &[&Path]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_relayd"))
        .args(args)
        .output()
        .unwrap()
}

fn import(args: &[&Path]) -> Output {
    let locks = tempfile::tempdir().unwrap();
    let lock = locks.path().join("devices.lock");
    let all: Vec<&Path> = [Path::new("import"), "--lock".as_ref(), &lock]
        .into_iter()
        .chain(args.iter().copied())
        .collect();
    relayd(&all)
}

fn stderr(output: &Output) -> String {
    String::from_utf8(output.stderr.clone()).unwrap()
}

#[test]
fn import_then_render_writes_the_deployed_config_back() {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("relay.json");
    let config = dir.path().join("config.json");

    let imported = import(&[
        "--config".as_ref(),
        &fixture("relay-direct.json"),
        "--devices".as_ref(),
        &fixture("relay-direct.devices.json"),
        "--out".as_ref(),
        &state,
    ]);
    assert!(imported.status.success(), "{}", stderr(&imported));
    assert_no_secret(&String::from_utf8(imported.stdout).unwrap());
    assert!(dir.path().join("relay.secrets.json").exists());

    let rendered = relayd(&[
        "render".as_ref(),
        "--state".as_ref(),
        &state,
        "--out".as_ref(),
        &config,
        "--group".as_ref(),
        "relayd-test-no-such-group".as_ref(),
    ]);
    assert!(rendered.status.success(), "{}", stderr(&rendered));
    assert_eq!(
        fs::read_to_string(&config).unwrap(),
        read("relay-direct.json")
    );
}

#[test]
fn devices_json_defaults_to_the_one_beside_the_config() {
    let dir = tempfile::tempdir().unwrap();
    fs::copy(fixture("today.json"), dir.path().join("config.json")).unwrap();
    fs::copy(
        fixture("today.devices.json"),
        dir.path().join("devices.json"),
    )
    .unwrap();

    let imported = import(&[
        "--config".as_ref(),
        &dir.path().join("config.json"),
        "--out".as_ref(),
        &dir.path().join("relay.json"),
    ]);

    assert!(imported.status.success(), "{}", stderr(&imported));
}

#[test]
fn a_refused_import_exits_nonzero_and_writes_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("relay.json");

    let refused = import(&[
        "--config".as_ref(),
        &fixture("relay-direct.json"),
        "--devices".as_ref(),
        &dir.path().join("missing.json"),
        "--out".as_ref(),
        &state,
    ]);

    assert_eq!(refused.status.code(), Some(1));
    assert!(stderr(&refused).contains("clients not in devices.json (shared laptop)"));
    assert_no_secret(&stderr(&refused));
    assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 0);
}

#[test]
fn a_malformed_command_line_prints_usage() {
    for args in [
        vec!["import".as_ref()],
        vec!["render".as_ref(), "--state".as_ref()],
        vec!["render".as_ref(), "--bogus".as_ref(), "x".as_ref()],
        vec!["restart".as_ref()],
        vec![
            "render".as_ref(),
            "--state".as_ref(),
            "s".as_ref(),
            "--out".as_ref(),
            "o".as_ref(),
            "--xray".as_ref(),
            "true".as_ref(),
        ],
    ] {
        let output = relayd(&args);
        assert_eq!(output.status.code(), Some(64));
        assert!(stderr(&output).starts_with("usage: relayd"));
    }
}

#[test]
fn render_refuses_to_replace_a_config_without_swap() {
    let dir = tempfile::tempdir().unwrap();
    let config = dir.path().join("config.json");
    fs::write(&config, "live").unwrap();

    let refused = relayd(&[
        "render".as_ref(),
        "--state".as_ref(),
        &fixture("relay-direct.state.json"),
        "--secrets".as_ref(),
        &fixture("relay-direct.secrets.json"),
        "--out".as_ref(),
        &config,
    ]);

    assert_eq!(refused.status.code(), Some(1));
    assert!(stderr(&refused).contains("refusing to overwrite"));
    assert_eq!(fs::read_to_string(&config).unwrap(), "live");

    let swapped = relayd(&[
        "render".as_ref(),
        "--swap".as_ref(),
        "--xray".as_ref(),
        "true".as_ref(),
        "--state".as_ref(),
        &fixture("relay-direct.state.json"),
        "--secrets".as_ref(),
        &fixture("relay-direct.secrets.json"),
        "--out".as_ref(),
        &config,
    ]);

    assert!(swapped.status.success(), "{}", stderr(&swapped));
    assert_eq!(
        fs::read_to_string(&config).unwrap(),
        read("relay-direct.json")
    );
}

#[test]
fn import_marks_the_named_device_an_admin() {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("relay.json");

    let imported = import(&[
        "--config".as_ref(),
        &fixture("relay-direct.json"),
        "--devices".as_ref(),
        &fixture("relay-direct.devices.json"),
        "--out".as_ref(),
        &state,
        "--admin".as_ref(),
        "laptop".as_ref(),
    ]);

    assert!(imported.status.success(), "{}", stderr(&imported));
    let state: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&state).unwrap()).unwrap();
    assert_eq!(state["devices"][0]["role"], "member");
    assert_eq!(state["devices"][1]["role"], "admin");
}

#[test]
fn import_refuses_an_admin_it_does_not_know() {
    let dir = tempfile::tempdir().unwrap();

    let refused = import(&[
        "--config".as_ref(),
        &fixture("relay-direct.json"),
        "--devices".as_ref(),
        &fixture("relay-direct.devices.json"),
        "--out".as_ref(),
        &dir.path().join("relay.json"),
        "--admin".as_ref(),
        "nobody".as_ref(),
    ]);

    assert_eq!(refused.status.code(), Some(1));
    assert!(stderr(&refused).contains("no device named nobody"));
    assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 0);
}

fn adopt(dir: &Path, services: &[&str], extra: &[&str]) -> Output {
    let mut config: serde_json::Value = serde_json::from_str(&read("relay-direct.json")).unwrap();
    config["api"]["services"] = services.into();
    fs::write(dir.join("config.json"), config.to_string()).unwrap();
    fs::copy(
        fixture("relay-direct.devices.json"),
        dir.join("devices.json"),
    )
    .unwrap();
    let locks = tempfile::tempdir().unwrap();
    let (config, state, lock) = (
        dir.join("config.json"),
        dir.join("relay.json"),
        locks.path().join("devices.lock"),
    );
    let mut args: Vec<&Path> = vec![
        "adopt".as_ref(),
        "--config".as_ref(),
        &config,
        "--state".as_ref(),
        &state,
        "--lock".as_ref(),
        &lock,
        "--xray".as_ref(),
        "true".as_ref(),
        "--group".as_ref(),
        "relayd-test-no-such-group".as_ref(),
    ];
    args.extend(extra.iter().map(Path::new));
    relayd(&args)
}

#[test]
fn adopting_a_stats_only_relay_adds_the_handler_service_and_says_xray_must_restart() {
    let dir = tempfile::tempdir().unwrap();

    let adopted = adopt(dir.path(), &["StatsService"], &["--admin", "laptop"]);

    assert!(adopted.status.success(), "{}", stderr(&adopted));
    let said = String::from_utf8(adopted.stdout).unwrap();
    assert!(said.contains("systemctl restart xray"), "{said}");
    assert_no_secret(&said);
    assert_eq!(
        fs::read_to_string(dir.path().join("config.json")).unwrap(),
        read("relay-direct.json")
    );
    assert!(dir.path().join("relay.json").exists());
    assert!(dir.path().join("relay.secrets.json").exists());
}

#[test]
fn adopting_a_relay_that_serves_the_handler_needs_no_restart() {
    let dir = tempfile::tempdir().unwrap();

    let adopted = adopt(dir.path(), &["HandlerService", "StatsService"], &[]);

    assert!(adopted.status.success(), "{}", stderr(&adopted));
    assert!(
        !String::from_utf8(adopted.stdout)
            .unwrap()
            .contains("restart")
    );
}

#[test]
fn adopt_refuses_to_replace_an_existing_state_and_leaves_the_config() {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join("relay.json"), "mine").unwrap();

    let refused = adopt(dir.path(), &["StatsService"], &[]);

    assert_eq!(refused.status.code(), Some(1));
    assert_eq!(
        fs::read_to_string(dir.path().join("relay.json")).unwrap(),
        "mine"
    );
    let config: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(dir.path().join("config.json")).unwrap()).unwrap();
    assert_eq!(
        config["api"]["services"],
        serde_json::json!(["StatsService"])
    );
}

#[test]
fn device_list_prints_every_device_and_no_secret() {
    let listed = relayd(&[
        "device".as_ref(),
        "list".as_ref(),
        "--state".as_ref(),
        &fixture("relay-direct.state.json"),
        "--secrets".as_ref(),
        &fixture("relay-direct.secrets.json"),
    ]);

    assert!(listed.status.success(), "{}", stderr(&listed));
    let table = String::from_utf8(listed.stdout).unwrap();
    assert_no_secret(&table);
    let lines: Vec<&str> = table.lines().collect();
    assert_eq!(lines.len(), 3, "{table}");
    assert!(lines[0].starts_with("NAME") && lines[0].ends_with("SEEN"));
    assert!(lines[1].starts_with("shared") && lines[1].contains("117.7 MiB"));
    assert!(lines[1].ends_with("2026-10-25T08:30:00Z"));
    assert!(lines[2].starts_with("laptop") && lines[2].ends_with("-"));
}

#[test]
fn a_device_change_with_no_xray_api_fails_before_touching_anything() {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("relay.json");
    fs::copy(fixture("relay-direct.state.json"), &state).unwrap();
    fs::copy(
        fixture("relay-direct.secrets.json"),
        dir.path().join("relay.secrets.json"),
    )
    .unwrap();
    let before = fs::read_to_string(&state).unwrap();

    let refused = relayd(&[
        "device".as_ref(),
        "rename".as_ref(),
        "shared".as_ref(),
        "desk".as_ref(),
        "--state".as_ref(),
        &state,
        "--config".as_ref(),
        &dir.path().join("config.json"),
        "--lock".as_ref(),
        &dir.path().join("devices.lock"),
    ]);

    assert_eq!(refused.status.code(), Some(1));
    assert!(
        stderr(&refused).contains("xray API"),
        "{}",
        stderr(&refused)
    );
    assert_no_secret(&stderr(&refused));
    assert_eq!(fs::read_to_string(&state).unwrap(), before);
}

#[test]
fn a_malformed_device_command_prints_usage() {
    for args in [
        vec!["device".as_ref()],
        vec!["device".as_ref(), "add".as_ref()],
        vec!["device".as_ref(), "list".as_ref()],
        vec![
            "device".as_ref(),
            "add".as_ref(),
            "phone".as_ref(),
            "--state".as_ref(),
            "s".as_ref(),
            "--config".as_ref(),
            "c".as_ref(),
            "--out".as_ref(),
            "o".as_ref(),
            "--role".as_ref(),
            "owner".as_ref(),
        ],
        vec![
            "device".as_ref(),
            "rename".as_ref(),
            "a".as_ref(),
            "--state".as_ref(),
            "s".as_ref(),
            "--config".as_ref(),
            "c".as_ref(),
        ],
        vec!["maintenance".as_ref()],
        vec!["maintenance".as_ref(), "refill-pool".as_ref()],
    ] {
        let output = relayd(&args);
        assert_eq!(output.status.code(), Some(64), "{args:?}");
        assert!(stderr(&output).starts_with("usage: relayd"));
    }
}

fn keyed_relay() -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("relay.json");
    fs::copy(fixture("relay-direct.state.json"), &state).unwrap();
    fs::copy(
        fixture("relay-direct.secrets.json"),
        dir.path().join("relay.secrets.json"),
    )
    .unwrap();
    (dir, state)
}

fn keys_add(dir: &Path, state: &Path, device: &str, public_key: &Path) -> Output {
    relayd(&[
        "keys".as_ref(),
        "add".as_ref(),
        device.as_ref(),
        "--role".as_ref(),
        "admin".as_ref(),
        "--public-key".as_ref(),
        public_key,
        "--state".as_ref(),
        state,
        "--lock".as_ref(),
        &dir.join("devices.lock"),
    ])
}

fn public_key_file(dir: &Path, seed: u8) -> PathBuf {
    let path = dir.join("key.der");
    fs::write(&path, common::public_der(&common::signer(seed))).unwrap();
    path
}

fn state_json(state: &Path) -> serde_json::Value {
    serde_json::from_str(&fs::read_to_string(state).unwrap()).unwrap()
}

#[test]
fn keys_add_stores_the_key_and_prints_only_its_id() {
    let (dir, state) = keyed_relay();

    let added = keys_add(
        dir.path(),
        &state,
        "laptop",
        &public_key_file(dir.path(), 7),
    );

    assert!(added.status.success(), "{}", stderr(&added));
    let relay = relayd::Relay::load(&state, &dir.path().join("relay.secrets.json")).unwrap();
    let key = &relay.state.keys[0];
    assert_eq!(
        (key.device.as_str(), key.role),
        ("laptop", relayd::Role::Admin)
    );
    let printed = String::from_utf8(added.stdout).unwrap();
    assert_eq!(printed, format!("added key {} for laptop\n", key.id));
}

#[test]
fn keys_add_refuses_a_stranger_a_repeat_and_junk_and_changes_nothing() {
    let (dir, state) = keyed_relay();
    let key = public_key_file(dir.path(), 7);
    let junk = dir.path().join("junk");
    fs::write(&junk, "not a key").unwrap();
    assert!(
        keys_add(dir.path(), &state, "laptop", &key)
            .status
            .success()
    );
    let before = fs::read_to_string(&state).unwrap();

    let refusals = [
        keys_add(dir.path(), &state, "ghost", &key),
        keys_add(dir.path(), &state, "laptop", &key),
        keys_add(dir.path(), &state, "shared", &junk),
    ];

    for refused in refusals {
        assert_eq!(refused.status.code(), Some(1));
        assert!(stderr(&refused).starts_with("relayd: "));
    }
    assert_eq!(fs::read_to_string(&state).unwrap(), before);
}

#[test]
fn a_malformed_keys_or_serve_command_prints_usage() {
    for args in [
        vec!["keys".as_ref()],
        vec!["keys".as_ref(), "add".as_ref(), "laptop".as_ref()],
        vec!["keys".as_ref(), "remove".as_ref()],
        vec!["serve".as_ref()],
        vec!["serve".as_ref(), "extra".as_ref()],
    ] {
        let output = relayd(&args);
        assert_eq!(output.status.code(), Some(64), "{args:?}");
    }
}

#[test]
fn serve_refuses_a_relay_with_no_stats_api() {
    let (dir, state) = keyed_relay();
    let mut json = state_json(&state);
    json["api"] = serde_json::Value::Null;
    fs::write(&state, json.to_string()).unwrap();

    let refused = relayd(&[
        "serve".as_ref(),
        "--state".as_ref(),
        &state,
        "--config".as_ref(),
        &dir.path().join("config.json"),
    ]);

    assert_eq!(refused.status.code(), Some(1));
    assert!(stderr(&refused).contains("no stats API"));
}

#[test]
fn serve_listens_on_loopback_and_nowhere_else() {
    let (dir, state) = keyed_relay();
    let port = std::net::TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let mut json = state_json(&state);
    json["agent"]["port"] = port.into();
    fs::write(&state, json.to_string()).unwrap();
    let mut server = Command::new(env!("CARGO_BIN_EXE_relayd"))
        .args(["serve", "--state"])
        .arg(&state)
        .arg("--config")
        .arg(dir.path().join("config.json"))
        .arg("--lock")
        .arg(dir.path().join("devices.lock"))
        .spawn()
        .unwrap();

    let listening = (0..100).any(|_| {
        std::thread::sleep(std::time::Duration::from_millis(50));
        std::net::TcpStream::connect(("127.0.0.1", port)).is_ok()
    });
    let table = fs::read_to_string("/proc/net/tcp").unwrap();
    server.kill().unwrap();
    server.wait().unwrap();

    assert!(listening, "the agent never listened on {port}");
    let bound: Vec<&str> = table
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace().skip(1);
            let (address, _remote, state) = (fields.next()?, fields.next()?, fields.next()?);
            (state == "0A" && address.ends_with(&format!(":{port:04X}"))).then_some(address)
        })
        .collect();
    assert_eq!(bound, [format!("0100007F:{port:04X}")]);
}
