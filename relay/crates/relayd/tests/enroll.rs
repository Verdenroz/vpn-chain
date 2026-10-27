mod common;

use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use common::fakes::{FakeLive, FakeRestart};
use common::{
    assert_no_secret, fixture, http_request, parse_reply, public_der, read, signed_headers, signer,
};
use relayd::agent::{Audit, Enroll, Service, Verifier, serve_on};
use relayd::{
    Enrollments, Placement, Relay, Role, TOKEN_TTL, Verdict, add_key, secrets_path, write_config,
};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::runtime::Builder;

const WAIT: Duration = Duration::from_secs(1);
const NOW: u64 = 1_800_000_000;

fn seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

struct Rig {
    dir: tempfile::TempDir,
    port: u16,
    counter: AtomicU64,
}

struct Reply {
    status: u16,
    body: Value,
}

impl Rig {
    fn start() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let state = dir.path().join("relay.json");
        let mut json: Value = serde_json::from_str(&read("relay-direct.state.json")).unwrap();
        json["spareShortIds"] = (0..3).map(|i| format!("{i:016x}")).collect();
        fs::write(&state, json.to_string()).unwrap();
        fs::copy(fixture("relay-direct.secrets.json"), secrets_path(&state)).unwrap();
        let relay = Relay::load(&state, &secrets_path(&state)).unwrap();
        let config = dir.path().join("config.json");
        let placement = Placement {
            group: "relayd-test-no-such-group",
            swap: None,
        };
        write_config(&relay, &config, &placement).unwrap();
        let service = Service {
            enroll: Enroll::beside(&state),
            secrets: secrets_path(&state),
            state,
            config,
            lock: dir.path().join("devices.lock"),
            xray: PathBuf::from("true"),
            group: "relayd-test-no-such-group".to_owned(),
            spares: 3,
            live: Arc::new(FakeLive::default()),
            restart: Arc::new(FakeRestart::default()),
            jobs: None,
            audit: Audit::open(&dir.path().join("audit.jsonl")).unwrap(),
            verifier: Verifier::default(),
        };
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            let runtime = Builder::new_current_thread().enable_all().build().unwrap();
            runtime.block_on(async {
                let listener = TcpListener::from_std(listener).unwrap();
                serve_on(Arc::new(service), listener).await.unwrap();
            });
        });
        Self {
            dir,
            port,
            counter: AtomicU64::new(0),
        }
    }

    fn state(&self) -> PathBuf {
        self.dir.path().join("relay.json")
    }

    fn lock(&self) -> PathBuf {
        self.dir.path().join("devices.lock")
    }

    fn relay(&self) -> Relay {
        Relay::load(&self.state(), &secrets_path(&self.state())).unwrap()
    }

    fn tokens(&self) -> Enrollments {
        Enrollments::beside(&self.state())
    }

    fn token_for(&self, device: &str, role: Role) -> String {
        self.tokens()
            .issue(&self.lock(), WAIT, device, role, seconds())
            .unwrap()
            .expose()
            .to_owned()
    }

    fn audit_text(&self) -> String {
        fs::read_to_string(self.dir.path().join("audit.jsonl")).unwrap()
    }

    fn send(&self, method: &str, target: &str, body: &str, headers: &[(String, String)]) -> Reply {
        let mut stream = TcpStream::connect(("127.0.0.1", self.port)).unwrap();
        stream
            .write_all(http_request("127.0.0.1", method, target, body, headers).as_bytes())
            .unwrap();
        let mut raw = Vec::new();
        stream.read_to_end(&mut raw).unwrap();
        let (status, body) = parse_reply(&raw).unwrap();
        Reply { status, body }
    }

    fn enroll(&self, token: &str, seed: u8) -> Reply {
        let public_key = STANDARD.encode(public_der(&signer(seed)));
        let body = json!({ "token": token, "publicKey": public_key }).to_string();
        self.send("POST", "/v1/enroll", &body, &[])
    }

    fn signed_get(&self, seed: u8, target: &str) -> Reply {
        let nonce = self.counter.fetch_add(1, Ordering::SeqCst);
        let headers = signed_headers(&signer(seed), "GET", target, "", nonce);
        self.send("GET", target, "", &headers)
    }
}

#[test]
fn a_token_is_good_once() {
    let rig = Rig::start();
    let tokens = rig.tokens();
    let token = tokens
        .issue(&rig.lock(), WAIT, "laptop", Role::Admin, NOW)
        .unwrap();

    let first = tokens
        .claim(&rig.lock(), WAIT, token.expose(), NOW + 1)
        .unwrap();
    let second = tokens
        .claim(&rig.lock(), WAIT, token.expose(), NOW + 2)
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

#[test]
fn a_token_is_expired_after_fifteen_minutes_and_burned_by_the_attempt() {
    let rig = Rig::start();
    let tokens = rig.tokens();
    let token = tokens
        .issue(&rig.lock(), WAIT, "laptop", Role::Member, NOW)
        .unwrap();

    let late = tokens
        .claim(&rig.lock(), WAIT, token.expose(), NOW + TOKEN_TTL + 1)
        .unwrap();
    let again = tokens
        .claim(&rig.lock(), WAIT, token.expose(), NOW + 1)
        .unwrap();

    assert_eq!((late, again), (Verdict::Expired, Verdict::Unknown));
}

#[test]
fn a_new_token_replaces_the_devices_earlier_one() {
    let rig = Rig::start();
    let tokens = rig.tokens();
    let old = tokens
        .issue(&rig.lock(), WAIT, "laptop", Role::Member, NOW)
        .unwrap();
    let other = tokens
        .issue(&rig.lock(), WAIT, "shared", Role::Member, NOW)
        .unwrap();
    tokens
        .issue(&rig.lock(), WAIT, "laptop", Role::Member, NOW)
        .unwrap();

    let replaced = tokens
        .claim(&rig.lock(), WAIT, old.expose(), NOW + 1)
        .unwrap();
    let kept = tokens
        .claim(&rig.lock(), WAIT, other.expose(), NOW + 1)
        .unwrap();

    assert_eq!(replaced, Verdict::Unknown);
    assert!(matches!(kept, Verdict::Claimed { .. }));
}

#[test]
fn the_store_keeps_a_hash_and_is_private() {
    let rig = Rig::start();
    let token = rig.token_for("laptop", Role::Admin);

    let path = rig.dir.path().join("enroll.json");
    let text = fs::read_to_string(&path).unwrap();

    assert!(!text.contains(&token));
    assert!(text.contains("laptop") && text.contains("\"hash\""));
    assert_eq!(
        fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o600
    );
}

#[test]
fn enrolling_registers_the_key_burns_the_token_and_rekeys_the_device() {
    let rig = Rig::start();
    let before = rig.relay().state.devices[1].clone();
    let token = rig.token_for(&before.name, Role::Admin);

    let reply = rig.enroll(&token, 7);

    assert_eq!(reply.status, 200, "{}", reply.body);
    let identity = &reply.body["identity"];
    assert_eq!(identity["name"], before.name);
    assert_ne!(identity["uuid"], before.uuid.expose());
    assert_ne!(identity["shortId"], before.short_id.expose());
    let key_id = reply.body["keyId"].as_str().unwrap();
    assert_eq!(key_id.len(), 16);
    let relay = rig.relay();
    let key = relay
        .state
        .keys
        .iter()
        .find(|key| key.id == key_id)
        .unwrap();
    assert_eq!(
        (key.device.as_str(), key.role),
        (before.name.as_str(), Role::Admin)
    );
    assert_eq!(relay.state.devices[1].uuid.expose(), identity["uuid"]);
}

#[test]
fn the_new_key_signs_requests_and_the_token_works_no_more() {
    let rig = Rig::start();
    let token = rig.token_for("laptop", Role::Member);
    assert_eq!(rig.enroll(&token, 7).status, 200);

    let signed = rig.signed_get(7, "/v1/relay");
    let reused = rig.enroll(&token, 8);

    assert_eq!(signed.status, 200);
    assert_eq!(reused.status, 401);
}

#[test]
fn an_expired_token_gets_410_and_a_made_up_one_401() {
    let rig = Rig::start();
    let stale = rig
        .tokens()
        .issue(
            &rig.lock(),
            WAIT,
            "laptop",
            Role::Member,
            seconds() - TOKEN_TTL - 5,
        )
        .unwrap();

    let expired = rig.enroll(stale.expose(), 7);
    let invented = rig.enroll("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 7);

    assert_eq!((expired.status, invented.status), (410, 401));
    assert!(rig.relay().state.keys.is_empty());
}

#[test]
fn a_bad_public_key_is_refused_and_leaves_the_token_usable() {
    let rig = Rig::start();
    let token = rig.token_for("laptop", Role::Member);

    let bad = rig.send(
        "POST",
        "/v1/enroll",
        &json!({ "token": token, "publicKey": "bm90IGEga2V5" }).to_string(),
        &[],
    );
    let good = rig.enroll(&token, 7);

    assert_eq!((bad.status, good.status), (400, 200));
}

#[test]
fn six_failures_in_a_minute_are_throttled_even_for_a_good_token() {
    let rig = Rig::start();
    let token = rig.token_for("laptop", Role::Member);
    for _ in 0..5 {
        assert_eq!(
            rig.enroll("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 7)
                .status,
            401
        );
    }

    let blocked = rig.enroll(&token, 7);

    assert_eq!(blocked.status, 429);
    assert!(rig.relay().state.keys.is_empty());
}

#[test]
fn the_audit_log_names_the_key_and_the_device_and_never_the_token() {
    let rig = Rig::start();
    let token = rig.token_for("laptop", Role::Member);
    let invented = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    rig.enroll(invented, 7);
    let reply = rig.enroll(&token, 7);

    let audit = rig.audit_text();

    assert!(audit.contains(reply.body["keyId"].as_str().unwrap()));
    assert!(audit.contains("\"target\":\"laptop\""));
    assert!(audit.contains("unknown token"));
    assert!(!audit.contains(&token) && !audit.contains(invented));
    assert_no_secret(&audit);
}

#[test]
fn a_key_that_is_already_added_cannot_enroll_again() {
    let rig = Rig::start();
    add_key(
        &rig.state(),
        &secrets_path(&rig.state()),
        &rig.lock(),
        WAIT,
        "shared",
        Role::Member,
        &public_der(&signer(7)),
    )
    .unwrap();
    let token = rig.token_for("laptop", Role::Member);

    let reply = rig.enroll(&token, 7);

    assert_eq!(reply.status, 409);
}

#[test]
fn every_other_path_still_wants_a_signature() {
    let rig = Rig::start();

    for target in ["/v1/relay", "/v1/devices", "/nowhere"] {
        assert_eq!(rig.send("GET", target, "", &[]).status, 401, "{target}");
    }
}
