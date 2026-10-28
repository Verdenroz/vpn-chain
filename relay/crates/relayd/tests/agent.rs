mod common;

use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use common::fakes::{FakeLive, FakeRestart};
use common::{
    assert_no_secret, fixture, http_request, key_for, parse_reply, public_der, read,
    signed_headers, signer,
};
use p256::ecdsa::SigningKey;
use relay_jobs::{Jobs, Layout};
use relayd::agent::{Audit, Enroll, Service, Verifier, serve_on};
use relayd::pairing::Public;
use relayd::{Placement, Relay, Role, secrets_path, write_config};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::runtime::Builder;

const ADMIN: u8 = 7;
const MEMBER: u8 = 8;
const SPARES: usize = 3;

/// An agent serving the `relay-direct` fixture on an ephemeral loopback port, with `laptop` an
/// admin key and `shared` a member key.
struct Rig {
    dir: tempfile::TempDir,
    port: u16,
    live: Arc<FakeLive>,
    restart: Arc<FakeRestart>,
    counter: AtomicU64,
}

struct Reply {
    status: u16,
    body: Value,
}

impl Rig {
    fn start(jobs: bool, spares: usize) -> Self {
        Self::start_with_xray(jobs, spares, "true")
    }

    fn start_with_xray(jobs: bool, spares: usize, xray: &str) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let state = dir.path().join("relay.json");
        let mut json: Value = serde_json::from_str(&read("relay-direct.state.json")).unwrap();
        json["devices"][1]["role"] = "admin".into();
        json["spareShortIds"] = (0..spares).map(|i| format!("{i:016x}")).collect();
        fs::write(&state, json.to_string()).unwrap();
        fs::copy(fixture("relay-direct.secrets.json"), secrets_path(&state)).unwrap();
        let mut relay = Relay::load(&state, &secrets_path(&state)).unwrap();
        relay.state.keys = vec![
            key_for("laptop", Role::Admin, &signer(ADMIN)),
            key_for("shared", Role::Member, &signer(MEMBER)),
        ];
        relay.save_state(&state).unwrap();
        Public::new("203.0.113.7", "mJZadK8WS3D6oFxqlXypqkrdiBeHBIYIvLY2GjDo0nA")
            .create(&Public::beside(&state))
            .unwrap();
        let config = dir.path().join("config.json");
        let placement = Placement {
            group: "relayd-test-no-such-group",
            swap: None,
        };
        write_config(&relay, &config, &placement).unwrap();
        let jobs = jobs.then(|| {
            for name in ["jobs", "requests", "confirm"] {
                fs::create_dir(dir.path().join(name)).unwrap();
            }
            Jobs::new(Layout::new(dir.path()))
        });
        let live = Arc::new(FakeLive::default());
        let restart = Arc::new(FakeRestart::default());
        let service = Service {
            secrets: secrets_path(&state),
            enroll: Enroll::beside(&state),
            state,
            config,
            lock: dir.path().join("devices.lock"),
            xray: PathBuf::from(xray),
            group: "relayd-test-no-such-group".to_owned(),
            spares: SPARES,
            live: live.clone(),
            restart: restart.clone(),
            jobs,
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
            live,
            restart,
            counter: AtomicU64::new(0),
        }
    }

    fn config(&self) -> String {
        fs::read_to_string(self.dir.path().join("config.json")).unwrap()
    }

    fn relay(&self) -> Relay {
        let state = self.dir.path().join("relay.json");
        Relay::load(&state, &secrets_path(&state)).unwrap()
    }

    fn audit_text(&self) -> String {
        fs::read_to_string(self.dir.path().join("audit.jsonl")).unwrap()
    }

    fn sign(
        &self,
        signer: &SigningKey,
        method: &str,
        target: &str,
        body: &str,
    ) -> Vec<(String, String)> {
        let nonce = self.counter.fetch_add(1, Ordering::SeqCst);
        signed_headers(signer, method, target, body, nonce)
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

    fn as_key(&self, seed: u8, method: &str, target: &str, body: &str) -> Reply {
        let headers = self.sign(&signer(seed), method, target, body);
        self.send(method, target, body, &headers)
    }

    fn admin(&self, method: &str, target: &str, body: &str) -> Reply {
        self.as_key(ADMIN, method, target, body)
    }

    fn member(&self, method: &str, target: &str, body: &str) -> Reply {
        self.as_key(MEMBER, method, target, body)
    }
}

#[test]
fn an_unsigned_request_is_refused_on_every_path() {
    let rig = Rig::start(false, SPARES);

    for target in ["/v1/relay", "/v1/devices", "/v1/audit", "/", "/nowhere"] {
        let reply = rig.send("GET", target, "", &[]);

        assert_eq!(reply.status, 401, "{target}");
        assert_eq!(reply.body, json!({ "error": "unauthorized" }));
    }
}

#[test]
fn a_request_signed_by_an_unknown_key_is_refused() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.as_key(9, "GET", "/v1/relay", "");

    assert_eq!(reply.status, 401);
}

#[test]
fn a_replayed_request_is_refused_the_second_time() {
    let rig = Rig::start(false, SPARES);
    let headers = rig.sign(&signer(ADMIN), "GET", "/v1/relay", "");

    let first = rig.send("GET", "/v1/relay", "", &headers);
    let second = rig.send("GET", "/v1/relay", "", &headers);

    assert_eq!((first.status, second.status), (200, 401));
    assert!(rig.audit_text().contains("replayed nonce"));
}

#[test]
fn the_audit_log_survives_a_torn_line() {
    let rig = Rig::start(false, SPARES);
    rig.admin("DELETE", "/v1/devices/shared", "");
    let path = rig.dir.path().join("audit.jsonl");
    let mut log = fs::read_to_string(&path).unwrap();
    log.push_str("{\"time\":\"2026");
    fs::write(&path, log).unwrap();

    let reply = rig.admin("GET", "/v1/audit", "");

    assert_eq!(reply.status, 200);
    assert_eq!(reply.body["entries"].as_array().unwrap().len(), 1);
}

#[test]
fn a_signature_made_for_one_path_does_not_open_another() {
    let rig = Rig::start(false, SPARES);
    let headers = rig.sign(&signer(ADMIN), "GET", "/v1/relay", "");

    let reply = rig.send("GET", "/v1/audit", "", &headers);

    assert_eq!(reply.status, 401);
}

#[test]
fn a_body_over_the_limit_is_refused() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("POST", "/v1/devices", &"x".repeat(20_000));

    assert_eq!(reply.status, 413);
}

#[test]
fn the_relay_overview_shows_versions_and_counts_and_no_secrets() {
    let rig = Rig::start(true, SPARES);

    let reply = rig.member("GET", "/v1/relay", "");

    assert_eq!(reply.status, 200);
    assert_eq!(
        reply.body,
        json!({
            "relayd": env!("CARGO_PKG_VERSION"),
            "xray": "26.3.27",
            "devices": 2,
            "pool": SPARES,
            "jobs": true,
        })
    );
}

#[test]
fn an_admin_lists_every_device_and_a_member_only_its_own() {
    let rig = Rig::start(false, SPARES);

    let admin = rig.admin("GET", "/v1/devices", "");
    let member = rig.member("GET", "/v1/devices", "");

    let names = |reply: &Reply| -> Vec<String> {
        reply.body["devices"]
            .as_array()
            .unwrap()
            .iter()
            .map(|device| device["name"].as_str().unwrap().to_owned())
            .collect()
    };
    assert_eq!(names(&admin), ["shared", "laptop"]);
    assert_eq!(names(&member), ["shared"]);
    assert_no_secret(&admin.body.to_string());
}

#[test]
fn an_admin_adds_a_device_and_gets_its_identity_in_the_response_only() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("POST", "/v1/devices", r#"{"name":"phone"}"#);

    assert_eq!(reply.status, 201);
    let identity = &reply.body["identity"];
    let relay = rig.relay();
    let phone = relay.state.devices.last().unwrap();
    assert_eq!(identity["name"], "phone");
    assert_eq!(identity["uuid"], phone.uuid.expose());
    assert_eq!(identity["shortId"], phone.short_id.expose());
    assert_eq!(rig.live.calls(), ["drain", "add phone"]);
    assert_eq!(rig.restart.count(), 0);
    let audit = rig.audit_text();
    assert!(!audit.contains(phone.uuid.expose()), "{audit}");
    assert!(!audit.contains(phone.short_id.expose()), "{audit}");
}

#[test]
fn a_member_cannot_add_a_device_and_the_refusal_is_audited() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.member("POST", "/v1/devices", r#"{"name":"phone"}"#);

    assert_eq!(reply.status, 403);
    assert_eq!(rig.relay().state.devices.len(), 2);
    assert!(rig.live.calls().is_empty());
    let audit = rig.audit_text();
    assert!(
        audit.contains(r#""action":"devices.add","target":"phone","result":"forbidden""#),
        "{audit}"
    );
}

#[test]
fn the_role_matrix_holds_for_a_member_on_every_admin_route() {
    let rig = Rig::start(true, SPARES);
    let id = "0123456789abcdef0123456789abcdef";
    let admin_only = [
        ("POST", "/v1/devices".to_owned(), r#"{"name":"phone"}"#),
        (
            "PATCH",
            "/v1/devices/laptop".to_owned(),
            r#"{"name":"desk"}"#,
        ),
        ("DELETE", "/v1/devices/laptop".to_owned(), ""),
        ("POST", "/v1/devices/laptop/rekey".to_owned(), ""),
        ("POST", "/v1/devices/laptop/pair".to_owned(), ""),
        ("POST", "/v1/pool/refill".to_owned(), ""),
        ("POST", "/v1/jobs".to_owned(), r#"{"kind":"restart-xray"}"#),
        ("GET", format!("/v1/jobs/{id}"), ""),
        ("POST", format!("/v1/jobs/{id}/confirm"), ""),
        ("GET", "/v1/audit".to_owned(), ""),
    ];

    for (method, target, body) in admin_only {
        let reply = rig.member(method, &target, body);

        assert_eq!(reply.status, 403, "{method} {target}");
    }
    assert!(rig.live.calls().is_empty());
    assert_eq!(rig.relay().state.devices.len(), 2);
}

#[test]
fn a_member_rekeys_its_own_device_and_no_other() {
    let rig = Rig::start(false, SPARES);
    let before = rig.relay().state.devices[0].uuid.clone();

    let own = rig.member("POST", "/v1/devices/shared/rekey", "");
    let other = rig.member("POST", "/v1/devices/laptop/rekey", "");

    assert_eq!((own.status, other.status), (200, 403));
    let after = rig.relay().state.devices[0].uuid.clone();
    assert_ne!(after, before);
    assert_eq!(own.body["identity"]["uuid"], after.expose());
}

#[test]
fn an_admin_gets_a_pairing_link_for_another_device_and_the_audit_omits_it() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("POST", "/v1/devices/shared/pair", "");

    assert_eq!(reply.status, 200);
    let link = reply.body["link"].as_str().unwrap();
    assert!(
        link.starts_with("vpnchain://pair?v=1&h=203.0.113.7&"),
        "{link}"
    );
    assert!(link.contains("&n=shared&"), "{link}");
    assert!(reply.body["expires"].as_u64().unwrap() > 0);
    let token = link.split("&t=").nth(1).unwrap().split('&').next().unwrap();
    let audit = rig.audit_text();
    assert!(audit.contains(r#""action":"devices.pair","target":"shared","result":"ok""#));
    assert!(!audit.contains(token), "{audit}");
}

const ENTRY: &str = "[Interface]\nPrivateKey = kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=\nAddress = 10.8.0.2/32\n\n[Peer]\nPublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.9:51820\n";

fn token_of(link: &Value) -> &str {
    let link = link.as_str().unwrap();
    link.split("&t=").nth(1).unwrap().split('&').next().unwrap()
}

impl Rig {
    fn enroll(&self, token: &str, seed: u8) -> Reply {
        let public_key = STANDARD.encode(public_der(&signer(seed)));
        let body = json!({ "token": token, "publicKey": public_key }).to_string();
        self.send("POST", "/v1/enroll", &body, &[])
    }
}

#[test]
fn a_pairing_with_an_entry_hop_hands_it_over_once_at_enroll() {
    let rig = Rig::start(false, SPARES);
    let body = json!({ "entryHop": ENTRY }).to_string();

    let paired = rig.admin("POST", "/v1/devices/shared/pair", &body);
    let enrolled = rig.enroll(token_of(&paired.body["link"]), 9);

    assert_eq!((paired.status, enrolled.status), (200, 200));
    assert_eq!(enrolled.body["entryHop"], ENTRY);
    assert!(!paired.body.to_string().contains("kPwG0r8"));
    assert!(!rig.audit_text().contains("kPwG0r8"));
}

#[test]
fn a_pairing_without_an_entry_hop_enrolls_without_one() {
    let rig = Rig::start(false, SPARES);

    let paired = rig.admin("POST", "/v1/devices/shared/pair", r#"{}"#);
    let enrolled = rig.enroll(token_of(&paired.body["link"]), 9);

    assert_eq!((paired.status, enrolled.status), (200, 200));
    assert!(enrolled.body.get("entryHop").is_none());
}

#[test]
fn an_invalid_entry_hop_is_a_400_that_issues_no_code_and_leaks_nothing() {
    let rig = Rig::start(false, SPARES);
    let split = ENTRY.replace("0.0.0.0/0", "10.0.0.0/8");

    let reply = rig.admin(
        "POST",
        "/v1/devices/shared/pair",
        &json!({ "entryHop": split }).to_string(),
    );

    assert_eq!(reply.status, 400);
    assert!(!reply.body.to_string().contains("kPwG0r8"));
    assert!(!rig.audit_text().contains("kPwG0r8"));
    assert!(!rig.dir.path().join("enroll.json").exists());
}

#[test]
fn pairing_an_unknown_device_is_a_404_and_the_callers_own_is_a_409() {
    let rig = Rig::start(false, SPARES);

    let unknown = rig.admin("POST", "/v1/devices/ghost/pair", "");
    let own = rig.admin("POST", "/v1/devices/laptop/pair", "");

    assert_eq!((unknown.status, own.status), (404, 409));
}

#[test]
fn an_admin_cannot_cut_off_its_own_device() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("DELETE", "/v1/devices/laptop", "");

    assert_eq!(reply.status, 409);
    assert_eq!(rig.relay().state.devices.len(), 2);
    assert!(rig.live.calls().is_empty());
}

#[test]
fn an_admin_renames_and_the_key_follows_the_device() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("PATCH", "/v1/devices/laptop", r#"{"name":"desk"}"#);
    let again = rig.admin("GET", "/v1/devices", "");

    assert_eq!(reply.status, 200);
    assert_eq!(reply.body, json!({ "name": "desk" }));
    assert_eq!(again.status, 200);
    assert_eq!(rig.relay().state.keys[0].device, "desk");
}

#[test]
fn refusals_map_to_the_status_that_says_what_to_do() {
    let rig = Rig::start(false, 0);

    let cases = [
        (
            rig.admin("POST", "/v1/devices", r#"{"name":"Bad Name"}"#)
                .status,
            400,
        ),
        (rig.admin("POST", "/v1/devices", "not json").status, 400),
        (
            rig.admin("POST", "/v1/devices", r#"{"name":"x","extra":1}"#)
                .status,
            400,
        ),
        (
            rig.admin("POST", "/v1/devices", r#"{"name":"laptop"}"#)
                .status,
            409,
        ),
        (
            rig.admin("POST", "/v1/devices", r#"{"name":"phone"}"#)
                .status,
            409,
        ),
        (rig.admin("DELETE", "/v1/devices/ghost", "").status, 404),
        (
            rig.admin("PATCH", "/v1/devices/ghost", r#"{"name":"spirit"}"#)
                .status,
            404,
        ),
    ];

    for (got, want) in cases {
        assert_eq!(got, want);
    }
}

#[test]
fn revoking_without_a_job_runner_restarts_directly() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("DELETE", "/v1/devices/shared", "");

    assert_eq!(reply.status, 200);
    assert_eq!(reply.body["job"], Value::Null);
    assert_eq!(rig.restart.count(), 1);
}

#[test]
fn revoking_with_a_job_runner_returns_a_job_to_confirm() {
    let rig = Rig::start(true, SPARES);

    let reply = rig.admin("DELETE", "/v1/devices/shared", "");

    assert_eq!(reply.status, 202);
    let id = reply.body["job"].as_str().unwrap().to_owned();
    assert_eq!(rig.restart.count(), 0);
    let job = rig.admin("GET", &format!("/v1/jobs/{id}"), "");
    assert_eq!(job.body["state"], "queued");
    assert_eq!(job.body["kind"], "restart-xray");
    let early = rig.admin("POST", &format!("/v1/jobs/{id}/confirm"), "");
    assert_eq!(early.status, 409);
}

#[test]
fn a_revoked_devices_key_stops_working() {
    let rig = Rig::start(false, SPARES);

    rig.admin("DELETE", "/v1/devices/shared", "");

    assert_eq!(rig.member("GET", "/v1/relay", "").status, 401);
}

#[test]
fn an_admin_queues_and_reads_jobs_and_a_bad_job_is_refused() {
    let rig = Rig::start(true, SPARES);

    let queued = rig.admin(
        "POST",
        "/v1/jobs",
        r#"{"kind":"swap-xray","version":"26.3.27"}"#,
    );
    let refused = [
        r#"{"kind":"apply-config"}"#,
        r#"{"kind":"swap-xray","version":"latest"}"#,
        r#"{"kind":"rm"}"#,
    ]
    .map(|body| rig.admin("POST", "/v1/jobs", body).status);

    assert_eq!(queued.status, 202);
    let id = queued.body["id"].as_str().unwrap();
    assert_eq!(rig.admin("GET", &format!("/v1/jobs/{id}"), "").status, 200);
    assert_eq!(refused, [400, 400, 400]);
    assert_eq!(rig.admin("GET", "/v1/jobs/not-an-id", "").status, 400);
    let missing = "f".repeat(32);
    assert_eq!(
        rig.admin("GET", &format!("/v1/jobs/{missing}"), "").status,
        404
    );
}

#[test]
fn the_job_routes_say_when_there_is_no_job_runner() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("POST", "/v1/jobs", r#"{"kind":"restart-xray"}"#);

    assert_eq!(reply.status, 501);
}

#[test]
fn the_audit_log_is_private_and_holds_who_did_what_and_no_credentials() {
    let rig = Rig::start(false, SPARES);
    let added = rig.admin("POST", "/v1/devices", r#"{"name":"phone"}"#);
    rig.member("POST", "/v1/devices", r#"{"name":"tablet"}"#);
    rig.admin("DELETE", "/v1/devices/phone", "");

    let reply = rig.admin("GET", "/v1/audit", "");

    assert_eq!(reply.status, 200);
    let entries = reply.body["entries"].as_array().unwrap();
    let actions: Vec<(&str, &str)> = entries
        .iter()
        .map(|entry| {
            (
                entry["action"].as_str().unwrap(),
                entry["result"].as_str().unwrap(),
            )
        })
        .collect();
    assert_eq!(
        actions,
        [
            ("devices.add", "ok"),
            ("devices.add", "forbidden"),
            ("devices.revoke", "ok")
        ]
    );
    let admin_key = key_for("laptop", Role::Admin, &signer(ADMIN)).id;
    assert_eq!(entries[0]["key"], admin_key);
    assert_eq!(entries[0]["target"], "phone");
    let log = rig.audit_text();
    assert_no_secret(&log);
    for secret in ["uuid", "shortId"] {
        assert!(!log.contains(added.body["identity"][secret].as_str().unwrap()));
    }
    let path = rig.dir.path().join("audit.jsonl");
    assert_eq!(
        fs::metadata(path).unwrap().permissions().mode() & 0o777,
        0o600
    );
}

const WG_CONF: &str = "[Interface]\nPrivateKey = kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n\n[Peer]\nPublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.9:51820\n";

fn set_wireguard(conf: &str) -> String {
    json!({ "kind": "wireguard", "conf": conf }).to_string()
}

#[test]
fn an_admin_reads_the_exit_without_any_key() {
    let rig = Rig::start(false, SPARES);
    let direct = rig.admin("GET", "/v1/exit", "");
    rig.admin("PUT", "/v1/exit", &set_wireguard(WG_CONF));

    let wireguard = rig.admin("GET", "/v1/exit", "");

    assert_eq!(direct.body, json!({ "kind": "direct" }));
    assert_eq!(
        wireguard.body,
        json!({ "kind": "wireguard", "endpoint": "203.0.113.9:51820" })
    );
}

#[test]
fn a_member_and_a_stranger_cannot_read_or_change_the_exit() {
    let rig = Rig::start(false, SPARES);

    let replies = [
        rig.member("GET", "/v1/exit", ""),
        rig.member("PUT", "/v1/exit", &set_wireguard(WG_CONF)),
        rig.send("GET", "/v1/exit", "", &[]),
    ];

    let statuses: Vec<u16> = replies.iter().map(|reply| reply.status).collect();
    assert_eq!(statuses, [403, 403, 401]);
    assert_eq!(rig.relay().state.exit, relayd::Exit::Direct);
    assert_eq!(rig.restart.count(), 0);
}

#[test]
fn a_wireguard_exit_is_stored_rendered_and_restarted_with_its_key_kept_in_secrets() {
    let rig = Rig::start(false, SPARES);

    let reply = rig.admin("PUT", "/v1/exit", &set_wireguard(WG_CONF));

    assert_eq!(reply.status, 200);
    assert_no_secret(&reply.body.to_string());
    let relay = rig.relay();
    assert!(matches!(relay.state.exit, relayd::Exit::WireGuard { .. }));
    assert_eq!(
        relay.secrets.exit_secret_key.unwrap().expose(),
        "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM="
    );
    let state = fs::read_to_string(rig.dir.path().join("relay.json")).unwrap();
    assert!(!state.contains("kPwG0r8"));
    let config: Value = serde_json::from_str(&rig.config()).unwrap();
    assert_eq!(config["routing"]["domainStrategy"], "AsIs");
    assert_eq!(rig.restart.count(), 1);
}

#[test]
fn changing_the_exit_with_a_job_runner_returns_a_job_to_confirm() {
    let rig = Rig::start(true, SPARES);

    let reply = rig.admin("PUT", "/v1/exit", &set_wireguard(WG_CONF));

    assert_eq!(reply.status, 202);
    let id = reply.body["job"].as_str().unwrap();
    let job = rig.admin("GET", &format!("/v1/jobs/{id}"), "");
    assert_eq!(job.body["kind"], "restart-xray");
    assert_eq!(rig.restart.count(), 0);
}

#[test]
fn going_back_to_direct_drops_the_key_and_the_tunnel() {
    let rig = Rig::start(false, SPARES);
    rig.admin("PUT", "/v1/exit", &set_wireguard(WG_CONF));

    let reply = rig.admin("PUT", "/v1/exit", r#"{"kind":"direct"}"#);

    assert_eq!(reply.status, 200);
    let relay = rig.relay();
    assert_eq!(relay.state.exit, relayd::Exit::Direct);
    assert!(relay.secrets.exit_secret_key.is_none());
    let config: Value = serde_json::from_str(&rig.config()).unwrap();
    assert_eq!(config["routing"]["domainStrategy"], "IPOnDemand");
    assert!(!rig.config().contains("exit-wg"));
}

#[test]
fn a_bad_exit_request_is_refused_before_anything_changes_and_echoes_no_key() {
    let rig = Rig::start(false, SPARES);
    let before = fs::read_to_string(rig.dir.path().join("relay.json")).unwrap();
    let no_endpoint = WG_CONF.replace("Endpoint", "Endpont");
    let short_key = WG_CONF.replace("kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=", "c2hvcnQ=");
    let bodies = [
        "not json".to_owned(),
        r#"{"kind":"vpn"}"#.to_owned(),
        r#"{"kind":"wireguard"}"#.to_owned(),
        set_wireguard(&no_endpoint),
        set_wireguard(&short_key),
    ];

    for body in bodies {
        let reply = rig.admin("PUT", "/v1/exit", &body);

        assert_eq!(reply.status, 400, "{body}");
        assert_no_secret(&reply.body.to_string());
    }
    assert_eq!(
        fs::read_to_string(rig.dir.path().join("relay.json")).unwrap(),
        before
    );
    assert_eq!(rig.restart.count(), 0);
}

#[test]
fn an_exit_xray_rejects_leaves_the_state_secrets_and_config_as_they_were() {
    let rig = Rig::start_with_xray(false, SPARES, "false");
    let config = rig.config();

    let reply = rig.admin("PUT", "/v1/exit", &set_wireguard(WG_CONF));

    assert_eq!(reply.status, 500);
    assert_no_secret(&reply.body.to_string());
    let relay = rig.relay();
    assert_eq!(relay.state.exit, relayd::Exit::Direct);
    assert!(relay.secrets.exit_secret_key.is_none());
    assert_eq!(rig.config(), config);
    assert_eq!(rig.restart.count(), 0);
}

#[test]
fn the_audit_log_names_an_exit_change_and_holds_no_key() {
    let rig = Rig::start(false, SPARES);
    rig.admin("PUT", "/v1/exit", &set_wireguard(WG_CONF));
    rig.member("PUT", "/v1/exit", &set_wireguard(WG_CONF));

    let log = rig.audit_text();

    assert_no_secret(&log);
    assert!(log.contains(r#""action":"exit.set","target":"wireguard","result":"ok""#));
    assert!(log.contains(r#""action":"exit.set","target":"wireguard","result":"forbidden""#));
}
