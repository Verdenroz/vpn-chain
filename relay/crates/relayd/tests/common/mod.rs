#![allow(dead_code)]
pub mod fakes;

use std::path::{Path, PathBuf};

use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::EncodePublicKey;
use relayd::agent::message;
use relayd::{Key, Relay, Role};

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

/// A signing key that is the same on every run, distinct per `seed`.
pub fn signer(seed: u8) -> SigningKey {
    SigningKey::from_bytes(&[seed; 32].into()).unwrap()
}

pub fn public_der(signer: &SigningKey) -> Vec<u8> {
    signer
        .verifying_key()
        .to_public_key_der()
        .unwrap()
        .as_bytes()
        .to_vec()
}

pub fn key_for(device: &str, role: Role, signer: &SigningKey) -> Key {
    Key::new(
        device,
        role,
        &public_der(signer),
        "2026-10-27T00:00:00Z".to_owned(),
    )
    .unwrap()
}

/// The four headers that sign a request made now; `nonce` must differ between requests.
pub fn signed_headers(
    signer: &SigningKey,
    method: &str,
    target: &str,
    body: &str,
    nonce: u64,
) -> Vec<(String, String)> {
    let key = key_for("x", Role::Member, signer).id;
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
        .to_string();
    let nonce = format!("{nonce:032x}");
    let text = message(method, target, body.as_bytes(), &timestamp, &nonce);
    let signature: Signature = signer.sign(text.as_bytes());
    vec![
        ("X-Key-Id".to_owned(), key),
        ("X-Timestamp".to_owned(), timestamp),
        ("X-Nonce".to_owned(), nonce),
        (
            "X-Signature".to_owned(),
            STANDARD.encode(signature.to_der().as_bytes()),
        ),
    ]
}

/// An HTTP/1.1 request that asks the server to close the connection after it.
pub fn http_request(
    host: &str,
    method: &str,
    target: &str,
    body: &str,
    headers: &[(String, String)],
) -> String {
    let mut request = format!(
        "{method} {target} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\nContent-Length: {}\r\n",
        body.len()
    );
    for (name, value) in headers {
        request.push_str(&format!("{name}: {value}\r\n"));
    }
    request.push_str("\r\n");
    request.push_str(body);
    request
}

/// The status and JSON body of a response read to the end, if it is one.
pub fn parse_reply(raw: &[u8]) -> Option<(u16, serde_json::Value)> {
    let raw = std::str::from_utf8(raw).ok()?;
    let (head, body) = raw.split_once("\r\n\r\n")?;
    let status = head
        .strip_prefix("HTTP/1.1 ")?
        .split(' ')
        .next()?
        .parse()
        .ok()?;
    Some((
        status,
        serde_json::from_str(body).unwrap_or(serde_json::Value::Null),
    ))
}
