mod common;

use std::fs;
use std::path::Path;
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
