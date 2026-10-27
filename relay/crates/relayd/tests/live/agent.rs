//! The agent behind a real xray: reached by name through the tunnel, by nothing else.

use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use live_harness::{ECHO_HOST, SING_BOX, Tunnel, roundtrip, wait_for_port};
use p256::ecdsa::SigningKey;
use relayd::agent::{Audit, Service, Verifier, serve_on};
use relayd::{AGENT_HOST, Role, XrayLive, add_key};
use serde_json::{Value, json};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Builder;
use tokio::time::timeout;

use crate::common::{http_request, parse_reply, public_der, signed_headers, signer};
use crate::site::{Credentials, NO_GROUP, SPARES, Site};

const ADMIN: u8 = 7;
const MEMBER: u8 = 8;
const HTTP: u16 = 80;

/// The agent serving the site's relay on its loopback port, with an admin key for `laptop`
/// and a member key for a second device, `tablet`.
struct Agent {
    admin: SigningKey,
    member: SigningKey,
    nonce: AtomicU64,
}

impl Agent {
    fn start(site: &Site) -> Self {
        site.manager()
            .add("tablet", Role::Member, &site.out("tablet"), false)
            .unwrap();
        let wait = Duration::from_secs(5);
        for (device, role, seed) in [
            ("laptop", Role::Admin, ADMIN),
            ("tablet", Role::Member, MEMBER),
        ] {
            let key = public_der(&signer(seed));
            add_key(
                &site.state,
                &site.secrets,
                &site.lock,
                wait,
                device,
                role,
                &key,
            )
            .unwrap();
        }
        let service = Service {
            state: site.state.clone(),
            secrets: site.secrets.clone(),
            config: site.config.clone(),
            lock: site.lock.clone(),
            xray: site.xray.clone(),
            group: NO_GROUP.to_owned(),
            spares: SPARES,
            live: Arc::new(XrayLive::connect(site.lab.api_port).unwrap()),
            restart: Arc::new(site.restart_handle()),
            jobs: None,
            audit: Audit::open(&site.dir.path().join("audit.jsonl")).unwrap(),
            verifier: Verifier::default(),
        };
        let listener = std::net::TcpListener::bind(("127.0.0.1", site.lab.agent_port)).unwrap();
        listener.set_nonblocking(true).unwrap();
        std::thread::spawn(move || {
            let runtime = Builder::new_current_thread().enable_all().build().unwrap();
            runtime.block_on(async {
                let listener = TcpListener::from_std(listener).unwrap();
                serve_on(Arc::new(service), listener).await.unwrap();
            });
        });
        site.rt.block_on(wait_for_port(site.lab.agent_port));
        Self {
            admin: signer(ADMIN),
            member: signer(MEMBER),
            nonce: AtomicU64::new(0),
        }
    }

    fn headers(
        &self,
        key: &SigningKey,
        method: &str,
        target: &str,
        body: &str,
    ) -> Vec<(String, String)> {
        let nonce = self.nonce.fetch_add(1, Ordering::SeqCst);
        signed_headers(key, method, target, body, nonce)
    }
}

/// What came back from one request through the tunnel, if anything did.
fn through(site: &Site, proxy: u16, host: &str, port: u16, request: &str) -> Option<(u16, Value)> {
    let reply = site.rt.block_on(async {
        Tunnel::open_to(proxy, host, port)
            .await?
            .exchange(request.as_bytes())
            .await
    });
    parse_reply(&reply.ok()?)
}

fn to_agent(site: &Site, proxy: u16, request: &str) -> Option<(u16, Value)> {
    through(site, proxy, AGENT_HOST, HTTP, request)
}

#[test]
#[ignore = "needs docker"]
fn the_agent_answers_a_signed_request_through_the_tunnel() {
    let site = Site::start("agent-reach");
    let agent = Agent::start(&site);

    for version in SING_BOX {
        let proxy = site.client(&site.laptop, version);
        let headers = agent.headers(&agent.admin, "GET", "/v1/relay", "");
        let signed = http_request(AGENT_HOST, "GET", "/v1/relay", "", &headers);

        let (status, body) = to_agent(&site, proxy, &signed).unwrap();

        assert_eq!(status, 200, "sing-box {version}");
        assert_eq!(body["xray"], "26.3.27");
        assert_eq!(body["devices"], 2);
        let replayed = to_agent(&site, proxy, &signed).unwrap();
        let unsigned = to_agent(
            &site,
            proxy,
            &http_request(AGENT_HOST, "GET", "/v1/relay", "", &[]),
        )
        .unwrap();
        assert_eq!(replayed.0, 401, "sing-box {version}");
        assert_eq!(unsigned.0, 401, "sing-box {version}");
    }
}

#[test]
#[ignore = "needs docker"]
fn no_other_loopback_port_answers_by_address_through_the_tunnel() {
    let site = Site::start("agent-blocked");
    let agent = Agent::start(&site);

    for version in SING_BOX {
        let proxy = site.client(&site.laptop, version);
        let headers = agent.headers(&agent.admin, "GET", "/v1/relay", "");
        let signed = http_request(AGENT_HOST, "GET", "/v1/relay", "", &headers);
        assert_eq!(to_agent(&site, proxy, &signed).unwrap().0, 200);

        let headers = agent.headers(&agent.admin, "GET", "/v1/relay", "");
        let signed = http_request("127.0.0.1", "GET", "/v1/relay", "", &headers);
        let by_address = through(&site, proxy, "127.0.0.1", site.lab.agent_port, &signed);
        let api = through(&site, proxy, "127.0.0.1", site.lab.api_port, &signed);
        let echo = site.rt.block_on(roundtrip(proxy, site.echo, b"ping"));

        assert_eq!(
            by_address, None,
            "the agent's own port by address, sing-box {version}"
        );
        assert_eq!(api, None, "xray's API port, sing-box {version}");
        assert!(
            echo.is_err(),
            "the echo server's port by address, sing-box {version}"
        );
        assert!(
            site.works(proxy),
            "{ECHO_HOST} by name is how the lab reaches echo"
        );
    }
}

#[test]
#[ignore = "needs docker"]
fn an_external_scan_finds_the_reality_port_and_neither_the_agent_nor_the_api() {
    let site = Site::start("agent-scan");
    let _agent = Agent::start(&site);
    let host = outbound_address();

    let open = site.rt.block_on(scan(host));

    assert!(open.contains(&site.lab.vless_port), "{open:?}");
    for hidden in [site.lab.agent_port, site.lab.api_port] {
        assert!(
            !open.contains(&hidden),
            "{hidden} answered on {host}: {open:?}"
        );
    }
}

#[test]
#[ignore = "needs docker"]
fn a_signed_add_over_the_tunnel_creates_a_device_that_then_connects() {
    let site = Site::start("agent-add");
    let agent = Agent::start(&site);
    let before = site.process();

    for version in SING_BOX {
        let proxy = site.client(&site.laptop, version);
        let name = format!("phone-{}", version.replace('.', "-"));
        let body = json!({ "name": name }).to_string();
        let headers = agent.headers(&agent.admin, "POST", "/v1/devices", &body);

        let (status, reply) = to_agent(
            &site,
            proxy,
            &http_request(AGENT_HOST, "POST", "/v1/devices", &body, &headers),
        )
        .unwrap();

        assert_eq!(status, 201, "sing-box {version}: {reply}");
        let phone = Credentials {
            uuid: reply["identity"]["uuid"].as_str().unwrap().to_owned(),
            short_id: reply["identity"]["shortId"].as_str().unwrap().to_owned(),
        };
        assert!(
            site.works(site.client(&phone, version)),
            "sing-box {version}"
        );
    }

    assert_eq!(site.process(), before);
    assert_eq!(
        site.users(),
        ["laptop", "phone-1-13-14", "phone-1-14-3", "tablet"]
    );
    let audit = std::fs::read_to_string(site.dir.path().join("audit.jsonl")).unwrap();
    assert!(
        audit.contains(r#""action":"devices.add","target":"phone-1-14-3","result":"ok""#),
        "{audit}"
    );
}

#[test]
#[ignore = "needs docker"]
fn a_member_key_is_refused_an_add_through_the_tunnel() {
    let site = Site::start("agent-member");
    let agent = Agent::start(&site);
    let proxy = site.client(&site.laptop, SING_BOX[0]);
    let body = json!({ "name": "phone" }).to_string();
    let headers = agent.headers(&agent.member, "POST", "/v1/devices", &body);

    let (status, _) = to_agent(
        &site,
        proxy,
        &http_request(AGENT_HOST, "POST", "/v1/devices", &body, &headers),
    )
    .unwrap();

    assert_eq!(status, 403);
    assert_eq!(site.users(), ["laptop", "tablet"]);
    let own = agent.headers(&agent.member, "GET", "/v1/devices", "");
    let (status, listed) = to_agent(
        &site,
        proxy,
        &http_request(AGENT_HOST, "GET", "/v1/devices", "", &own),
    )
    .unwrap();
    assert_eq!(status, 200);
    assert_eq!(listed["devices"].as_array().unwrap().len(), 1);
}

/// The address this machine uses to reach other hosts, which a loopback-only listener does not
/// answer on.
fn outbound_address() -> IpAddr {
    let probe = UdpSocket::bind("0.0.0.0:0").unwrap();
    probe.connect("192.0.2.1:9").unwrap();
    probe.local_addr().unwrap().ip()
}

/// Every TCP port on `host` that accepts a connection.
async fn scan(host: IpAddr) -> Vec<u16> {
    let ports: Vec<u16> = (1..=u16::MAX).collect();
    let mut open = Vec::new();
    for batch in ports.chunks(512) {
        let attempts: Vec<_> = batch
            .iter()
            .map(|&port| {
                tokio::spawn(async move {
                    let address = SocketAddr::new(host, port);
                    let connected =
                        timeout(Duration::from_millis(500), TcpStream::connect(address));
                    connected
                        .await
                        .is_ok_and(|result| result.is_ok())
                        .then_some(port)
                })
            })
            .collect();
        for attempt in attempts {
            open.extend(attempt.await.unwrap());
        }
    }
    open
}
