#![allow(dead_code)]
pub mod fakes;

use std::path::{Path, PathBuf};

use relayd::Relay;

/// Every credential in the fixtures, none of which may reach a `Debug` or error string.
pub const SECRETS: [&str; 12] = [
    "yHxKfB2n0Pq6Zr8sT3uV5wX7yA9bC1dE3fG5hI7jK0M",
    "mJZadK8WS3D6oFxqlXypqkrdiBeHBIYIvLY2GjDo0nA",
    "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=",
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
    "33333333-3333-4333-8333-333333333333",
    "44444444-4444-4444-8444-444444444444",
    "0123456789abcdef",
    "a1b2c3d4e5f60718",
    "0f1e2d3c4b5a6978",
    "5566778899aabbcc",
    "pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
];

pub fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

pub fn read(name: &str) -> String {
    std::fs::read_to_string(fixture(name)).unwrap()
}

pub fn relay(name: &str) -> Relay {
    Relay::load(
        &fixture(&format!("{name}.state.json")),
        &fixture(&format!("{name}.secrets.json")),
    )
    .unwrap()
}

pub fn import(config: &Path, devices: &Path) -> Result<Relay, relayd::Error> {
    let dir = tempfile::tempdir().unwrap();
    relayd::import(config, devices, &dir.path().join("devices.lock"))
}

pub fn assert_no_secret(text: &str) {
    for secret in SECRETS {
        assert!(!text.contains(secret), "{secret} leaked into: {text}");
    }
}
