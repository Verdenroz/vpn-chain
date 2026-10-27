mod common;

use std::collections::HashMap;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::time::Duration;

use common::{fixture, read};
use relayd::pairing::{self, Public, Purpose};
use relayd::{Enrollments, FIRST_ADMIN_TTL, Kind, Role, TOKEN_TTL, Verdict, secrets_path};
use serde_json::Value;

const PBK: &str = "mJZadK8WS3D6oFxqlXypqkrdiBeHBIYIvLY2GjDo0nA";
const WAIT: Duration = Duration::from_secs(1);

struct Site {
    dir: tempfile::TempDir,
}

impl Site {
    fn new(host: &str) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let state = dir.path().join("relay.json");
        let mut json: Value = serde_json::from_str(&read("relay-direct.state.json")).unwrap();
        json["devices"][1]["role"] = "admin".into();
        fs::write(&state, json.to_string()).unwrap();
        fs::copy(fixture("relay-direct.secrets.json"), secrets_path(&state)).unwrap();
        Public::new(host, PBK)
            .create(&Public::beside(&state))
            .unwrap();
        Self { dir }
    }

    fn state(&self) -> PathBuf {
        self.dir.path().join("relay.json")
    }

    fn lock(&self) -> PathBuf {
        self.dir.path().join("devices.lock")
    }

    fn link(&self, device: Option<&str>) -> String {
        self.link_for(device, Purpose::Device)
    }

    fn link_for(&self, device: Option<&str>, purpose: Purpose) -> String {
        let state = self.state();
        pairing::issue(&state, &secrets_path(&state), &self.lock(), device, purpose)
            .unwrap()
            .expose()
            .to_owned()
    }
}

fn params(link: &str) -> HashMap<String, String> {
    let query = link.strip_prefix("vpnchain://pair?").unwrap();
    query
        .split('&')
        .map(|pair| {
            let (name, value) = pair.split_once('=').unwrap();
            (name.to_owned(), decode(value))
        })
        .collect()
}

fn decode(value: &str) -> String {
    let mut bytes = Vec::new();
    let mut raw = value.bytes();
    while let Some(byte) = raw.next() {
        if byte == b'%' {
            let hex: Vec<u8> = raw.by_ref().take(2).collect();
            bytes.push(u8::from_str_radix(std::str::from_utf8(&hex).unwrap(), 16).unwrap());
        } else {
            bytes.push(byte);
        }
    }
    String::from_utf8(bytes).unwrap()
}

#[test]
fn a_link_carries_what_the_app_needs_to_dial_and_enroll() {
    let site = Site::new("203.0.113.7");

    let link = site.link(Some("shared"));
    let params = params(&link);

    assert_eq!(params["v"], "1");
    assert_eq!(params["h"], "203.0.113.7");
    assert_eq!(params["p"], "443");
    assert_eq!(params["sni"], "www.samsung.com");
    assert_eq!(params["pbk"], PBK);
    assert_eq!(params["sid"], "a1b2c3d4e5f60718");
    assert_eq!(params["id"].len(), 36);
    assert_eq!(params["n"], "shared");
    assert_eq!(params["t"].len(), 43);
    assert!(params["e"].parse::<u64>().is_ok());
}

#[test]
fn the_token_in_the_link_is_the_one_the_store_accepts_once() {
    let site = Site::new("203.0.113.7");
    let params = params(&site.link(Some("laptop")));
    let tokens = Enrollments::beside(&site.state());

    let first = tokens
        .claim(&site.lock(), WAIT, &params["t"], pairing_now())
        .unwrap();
    let second = tokens
        .claim(&site.lock(), WAIT, &params["t"], pairing_now())
        .unwrap();

    assert_eq!(
        first,
        Verdict::Claimed {
            device: "laptop".to_owned(),
            role: Role::Admin
        }
    );
    assert_eq!(second, Verdict::Unknown);
}

fn pairing_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

#[test]
fn without_a_device_the_first_admin_is_paired() {
    let site = Site::new("203.0.113.7");

    let params = params(&site.link(None));

    assert_eq!(params["n"], "laptop");
}

#[test]
fn a_device_link_lasts_fifteen_minutes_and_a_first_admin_link_a_day() {
    let site = Site::new("203.0.113.7");

    let device = params(&site.link(None));
    let first = params(&site.link_for(None, Purpose::FirstAdmin));

    let lifetime =
        |params: &HashMap<String, String>| params["e"].parse::<u64>().unwrap() - pairing_now();
    assert!(lifetime(&device).abs_diff(TOKEN_TTL) <= 2);
    assert!(lifetime(&first).abs_diff(FIRST_ADMIN_TTL) <= 2);
}

#[test]
fn only_a_first_admin_link_carries_f() {
    let site = Site::new("203.0.113.7");

    assert!(!params(&site.link(None)).contains_key("f"));
    assert_eq!(params(&site.link_for(None, Purpose::FirstAdmin))["f"], "1");
}

#[test]
fn the_display_states_the_lifetime_of_the_link() {
    let site = Site::new("203.0.113.7");
    let state = site.state();
    let issue = |purpose| {
        let link =
            pairing::issue(&state, &secrets_path(&state), &site.lock(), None, purpose).unwrap();
        pairing::show(&link, purpose)
    };

    assert!(issue(Purpose::Device).contains("once and for 15 minutes."));
    assert!(issue(Purpose::FirstAdmin).contains("once and for 24 hours."));
}

#[test]
fn an_ipv6_host_is_bracketed_and_a_name_is_kept() {
    assert_eq!(
        params(&Site::new("2001:db8::7").link(None))["h"],
        "[2001:db8::7]"
    );
    assert_eq!(
        params(&Site::new("vpn.example.com").link(None))["h"],
        "vpn.example.com"
    );
}

#[test]
fn values_are_percent_encoded() {
    let link = Site::new("2001:db8::7").link(None);

    assert!(link.contains("h=%5B2001%3Adb8%3A%3A7%5D"), "{link}");
}

#[test]
fn an_unknown_device_is_refused() {
    let site = Site::new("203.0.113.7");
    let state = site.state();

    let error = pairing::issue(
        &state,
        &secrets_path(&state),
        &site.lock(),
        Some("ghost"),
        Purpose::Device,
    )
    .unwrap_err();

    assert_eq!(error.kind(), Kind::Missing);
}

#[test]
fn the_display_shows_the_link_once_and_a_qr_code() {
    let site = Site::new("203.0.113.7");
    let state = site.state();
    let link = pairing::issue(
        &state,
        &secrets_path(&state),
        &site.lock(),
        None,
        Purpose::Device,
    )
    .unwrap();

    let shown = pairing::show(&link, Purpose::Device);

    assert_eq!(shown.matches("vpnchain://").count(), 1);
    assert!(shown.contains('█') && shown.contains('▀'));
    assert!(!format!("{link:?}").contains("vpnchain"));
}

#[test]
fn the_public_file_is_private_and_holds_no_secret() {
    let site = Site::new("203.0.113.7");
    let path = Public::beside(&site.state());

    let text = fs::read_to_string(&path).unwrap();

    assert_eq!(
        fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert!(text.contains(PBK) && text.contains("203.0.113.7"));
    assert_eq!(
        Public::read(&path).unwrap(),
        Public::new("203.0.113.7", PBK)
    );
}
