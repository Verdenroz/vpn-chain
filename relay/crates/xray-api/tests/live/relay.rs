use std::net::{SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tokio::process::Command;
use uuid::Uuid;
use xray_api::{UserId, VlessUser, XrayApi};

pub const INBOUND: &str = "vless-reality-443";
/// libbox in the Android app, and the desktop's sing-box.
pub const SING_BOX: [&str; 2] = ["1.13.14", "1.14.3"];

const XRAY: &str = "xray-v26.3.27";
const BASE_IMAGE: &str =
    "debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f";
const DEST_IMAGE: &str =
    "caddy:2.10-alpine@sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d";
const SNI: &str = "dest.test";
const POOL: usize = 3;

/// A device's credentials: its VLESS user and the short ID it presents.
#[derive(Clone)]
pub struct Device {
    pub user: VlessUser,
    pub short_id: String,
}

impl Device {
    pub fn new(email: &str, short_id: &str) -> Self {
        Self {
            user: VlessUser {
                email: email.to_owned(),
                id: UserId::new(Uuid::new_v4()),
                level: 0,
            },
            short_id: short_id.to_owned(),
        }
    }
}

/// One xray relay in docker on the host network: a REALITY inbound with one
/// configured device and a pool of spare short IDs, and the API on loopback.
pub struct Relay {
    pub api: XrayApi,
    pub seed: Device,
    pub pool: Vec<String>,
    prefix: String,
    dir: PathBuf,
    vless_port: u16,
    public_key: String,
    containers: Mutex<Vec<String>>,
}

impl Relay {
    pub async fn start(name: &str) -> Self {
        let prefix = format!("xray-api-live-{}-{name}", std::process::id());
        let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join(&prefix);
        std::fs::create_dir_all(&dir).unwrap();
        let (private_key, public_key) = keypair().await;
        let (dest_port, vless_port, api_port) = (free_port(), free_port(), free_port());
        let relay = Self {
            api: XrayApi::new(
                SocketAddr::from(([127, 0, 0, 1], api_port)),
                Duration::from_secs(3),
            ),
            seed: Device::new("seed", &short_id()),
            pool: (0..POOL).map(|_| short_id()).collect(),
            prefix,
            dir,
            vless_port,
            public_key,
            containers: Mutex::default(),
        };

        std::fs::write(relay.dir.join("Caddyfile"), caddyfile(dest_port)).unwrap();
        relay
            .run(
                "dest",
                DEST_IMAGE,
                &["caddy", "run", "--config", "/etc/relay/Caddyfile"],
            )
            .await;
        wait_for_port(dest_port).await;

        let config = relay.xray_config(&private_key, dest_port, api_port);
        std::fs::write(relay.dir.join("xray.json"), config.to_string()).unwrap();
        let xray = format!("/opt/bins/{XRAY}/xray");
        relay
            .run(
                "xray",
                BASE_IMAGE,
                &[&xray, "run", "-c", "/etc/relay/xray.json"],
            )
            .await;
        relay.wait_for_api().await;
        relay
    }

    /// Starts a sing-box `version` client for `device`; returns its mixed inbound port.
    pub async fn client(&self, device: &Device, version: &str) -> u16 {
        let port = free_port();
        let file = format!("client-{}-{version}.json", device.user.email);
        let config = self.client_config(device, port);
        std::fs::write(self.dir.join(&file), config.to_string()).unwrap();
        let binary = format!("/opt/bins/sing-box-{version}/sing-box");
        let config = format!("/etc/relay/{file}");
        let name = format!("{}-{version}", device.user.email);
        self.run(&name, BASE_IMAGE, &[&binary, "run", "-c", &config])
            .await;
        wait_for_port(port).await;
        port
    }

    /// The host PID and start time of the xray process.
    pub async fn xray_process(&self) -> String {
        let name = format!("{}-xray", self.prefix);
        docker(&[
            "inspect",
            "-f",
            "{{.State.Pid}} {{.State.StartedAt}}",
            &name,
        ])
        .await
    }

    pub async fn restart_xray(&self) {
        docker(&["restart", "-t", "1", &format!("{}-xray", self.prefix)]).await;
        self.wait_for_api().await;
    }

    async fn run(&self, name: &str, image: &str, command: &[&str]) {
        let container = format!("{}-{name}", self.prefix);
        let bins = format!("{}:/opt/bins:ro", bins().display());
        let config = format!("{}:/etc/relay:ro", self.dir.display());
        let args = [
            &["run", "-d", "--name", &container, "--network", "host"][..],
            &["-v", &bins, "-v", &config, image],
            command,
        ]
        .concat();
        self.containers.lock().unwrap().push(container.clone());
        docker(&args).await;
    }

    async fn wait_for_api(&self) {
        let deadline = Instant::now() + Duration::from_secs(15);
        while self.api.users(INBOUND).await.is_err() {
            assert!(Instant::now() < deadline, "xray API never came up");
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    }

    fn xray_config(&self, private_key: &str, dest_port: u16, api_port: u16) -> Value {
        let short_ids: Vec<&str> = std::iter::once(&self.seed.short_id)
            .chain(&self.pool)
            .map(String::as_str)
            .collect();
        json!({
            "log": { "loglevel": "warning" },
            "stats": {},
            "api": { "tag": "api", "services": ["HandlerService", "StatsService"] },
            "policy": { "levels": { "0": { "statsUserUplink": true, "statsUserDownlink": true } } },
            "inbounds": [{
                "tag": INBOUND, "listen": "127.0.0.1", "port": self.vless_port, "protocol": "vless",
                "settings": {
                    "clients": [{
                        "id": self.seed.user.id.expose().to_string(),
                        "email": self.seed.user.email,
                        "flow": "xtls-rprx-vision",
                    }],
                    "decryption": "none",
                },
                "streamSettings": {
                    "network": "tcp", "security": "reality",
                    "realitySettings": {
                        "show": false, "dest": format!("127.0.0.1:{dest_port}"), "xver": 0,
                        "serverNames": [SNI], "privateKey": private_key, "shortIds": short_ids,
                    },
                },
                "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true },
            }, {
                "tag": "api", "listen": "127.0.0.1", "port": api_port, "protocol": "dokodemo-door",
                "settings": { "address": "127.0.0.1" },
            }],
            "outbounds": [{ "tag": "direct", "protocol": "freedom" }],
            "routing": { "rules": [
                { "type": "field", "inboundTag": ["api"], "outboundTag": "api" },
                { "type": "field", "network": "tcp,udp", "outboundTag": "direct" },
            ] },
        })
    }

    /// The VLESS outbound core/config's SingBoxConfigFactory renders for the SOCKS proxy.
    fn client_config(&self, device: &Device, port: u16) -> Value {
        json!({
            "log": { "level": "warn" },
            "inbounds": [{ "type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": port }],
            "outbounds": [{
                "type": "vless", "tag": "vless-proxy",
                "server": "127.0.0.1", "server_port": self.vless_port,
                "uuid": device.user.id.expose().to_string(),
                "flow": "xtls-rprx-vision",
                "network": "tcp",
                "tls": {
                    "enabled": true, "server_name": SNI,
                    "utls": { "enabled": true, "fingerprint": "chrome" },
                    "reality": { "enabled": true, "public_key": self.public_key, "short_id": device.short_id },
                },
                "packet_encoding": "xudp",
                "connect_timeout": "3s",
            }],
            "route": { "final": "vless-proxy" },
        })
    }
}

impl Drop for Relay {
    fn drop(&mut self) {
        let containers = self.containers.lock().unwrap();
        let _ = std::process::Command::new("docker")
            .args(["rm", "-f"])
            .args(containers.iter())
            .output();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn caddyfile(port: u16) -> String {
    format!(
        "{{\n\tadmin off\n\tlocal_certs\n\tskip_install_trust\n\tauto_https disable_redirects\n\
         \thttp_port {}\n\thttps_port {port}\n}}\n\
         https://{SNI}:{port} {{\n\tbind 127.0.0.1\n\trespond \"dest\"\n}}\n",
        free_port()
    )
}

async fn keypair() -> (String, String) {
    let bins = format!("{}:/opt/bins:ro", bins().display());
    let xray = format!("/opt/bins/{XRAY}/xray");
    let out = docker(&["run", "--rm", "-v", &bins, BASE_IMAGE, &xray, "x25519"]).await;
    let field = |key: &str| {
        out.lines()
            .find_map(|line| line.strip_prefix(key))
            .map(|value| value.trim().to_owned())
            .unwrap_or_else(|| panic!("no {key} in xray x25519 output"))
    };
    (field("PrivateKey:"), field("Password (PublicKey):"))
}

fn bins() -> &'static Path {
    static BINS: OnceLock<PathBuf> = OnceLock::new();
    BINS.get_or_init(|| {
        let script = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fetch-bins.sh");
        let out = std::process::Command::new(script).output().unwrap();
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        PathBuf::from(String::from_utf8(out.stdout).unwrap().trim())
    })
}

async fn docker(args: &[&str]) -> String {
    let out = Command::new("docker").args(args).output().await.unwrap();
    assert!(
        out.status.success(),
        "docker {args:?}: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8(out.stdout).unwrap().trim().to_owned()
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

fn short_id() -> String {
    Uuid::new_v4().simple().to_string()[..16].to_owned()
}

async fn wait_for_port(port: u16) {
    let deadline = Instant::now() + Duration::from_secs(15);
    while tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .is_err()
    {
        assert!(Instant::now() < deadline, "nothing listened on {port}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}
