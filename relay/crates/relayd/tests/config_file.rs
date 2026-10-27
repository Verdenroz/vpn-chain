mod common;

use std::fs;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::Path;

use common::{read, relay};
use relayd::{Placement, write_config};

const NO_GROUP: &str = "relayd-test-no-such-group";

fn mode(path: &Path) -> u32 {
    fs::metadata(path).unwrap().permissions().mode() & 0o777
}

fn own_group(dir: &Path) -> (String, u32) {
    let gid = fs::metadata(dir).unwrap().gid();
    let name = fs::read_to_string("/etc/group")
        .unwrap()
        .lines()
        .find_map(|line| {
            let fields: Vec<&str> = line.split(':').collect();
            (fields.get(2) == Some(&gid.to_string().as_str())).then(|| fields[0].to_owned())
        })
        .expect("the test user's group is in /etc/group");
    (name, gid)
}

fn names(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    names
}

#[test]
fn a_new_config_is_written_byte_for_byte_as_jq_writes_it() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    let placement = Placement {
        group: NO_GROUP,
        swap: None,
    };

    write_config(&relay("relay-direct"), &path, &placement).unwrap();

    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        read("relay-direct.json")
    );
}

#[test]
fn without_xrays_group_the_config_is_private_to_its_owner() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    let placement = Placement {
        group: NO_GROUP,
        swap: None,
    };

    write_config(&relay("relay-direct"), &path, &placement).unwrap();

    assert_eq!(mode(&path), 0o600);
}

#[test]
fn xrays_group_can_read_the_config_and_nobody_else() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    let (group, gid) = own_group(dir.path());
    let placement = Placement {
        group: &group,
        swap: None,
    };

    write_config(&relay("relay-direct"), &path, &placement).unwrap();

    assert_eq!(mode(&path), 0o640);
    assert_eq!(fs::metadata(&path).unwrap().gid(), gid);
}

#[test]
fn an_existing_config_is_never_replaced_without_swap() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    fs::write(&path, "live").unwrap();
    let placement = Placement {
        group: NO_GROUP,
        swap: None,
    };

    let error = write_config(&relay("relay-direct"), &path, &placement).unwrap_err();

    assert!(
        error.to_string().contains("refusing to overwrite"),
        "{error}"
    );
    assert_eq!(fs::read_to_string(&path).unwrap(), "live");
    assert_eq!(names(dir.path()), ["config.json"]);
}

#[test]
fn a_swap_xray_accepts_replaces_the_config_and_keeps_the_old_one() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    fs::write(&path, "live").unwrap();
    let placement = Placement {
        group: NO_GROUP,
        swap: Some(Path::new("true")),
    };

    write_config(&relay("relay-direct"), &path, &placement).unwrap();

    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        read("relay-direct.json")
    );
    let names = names(dir.path());
    assert_eq!(names.len(), 2, "{names:?}");
    assert!(names[1].starts_with("config.json.bak."), "{names:?}");
    assert_eq!(
        fs::read_to_string(dir.path().join(&names[1])).unwrap(),
        "live"
    );
}

#[test]
fn swaps_in_the_same_second_each_keep_their_own_backup() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    fs::write(&path, "live").unwrap();
    let placement = Placement {
        group: NO_GROUP,
        swap: Some(Path::new("true")),
    };

    for _ in 0..3 {
        write_config(&relay("relay-direct"), &path, &placement).unwrap();
    }

    assert_eq!(names(dir.path()).len(), 4);
}

#[test]
fn a_swap_xray_rejects_leaves_the_live_config_alone() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.json");
    fs::write(&path, "live").unwrap();
    let placement = Placement {
        group: NO_GROUP,
        swap: Some(Path::new("false")),
    };

    let error = write_config(&relay("relay-direct"), &path, &placement).unwrap_err();

    assert!(
        error
            .to_string()
            .contains("-test rejected the rendered config"),
        "{error}"
    );
    assert_eq!(fs::read_to_string(&path).unwrap(), "live");
    assert_eq!(names(dir.path()), ["config.json"]);
}
