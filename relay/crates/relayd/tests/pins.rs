use std::fs;
use std::path::Path;

use relayd::XRAY_VERSION;
use relayd::install::RELEASES;

fn pin(name: &str) -> String {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../server/lib/pins.sh");
    fs::read_to_string(path)
        .unwrap()
        .lines()
        .find_map(|line| line.strip_prefix(&format!("{name}=")))
        .unwrap()
        .to_owned()
}

#[test]
fn the_xray_version_is_the_one_the_server_scripts_pin() {
    assert_eq!(format!("v{XRAY_VERSION}"), pin("XRAY_VERSION"));
}

#[test]
fn the_downloads_are_the_ones_the_server_scripts_pin() {
    let [(_, _, amd64), (_, _, arm64)] = RELEASES;

    assert_eq!(amd64, pin("XRAY_SHA256_amd64"));
    assert_eq!(arm64, pin("XRAY_SHA256_arm64"));
}
