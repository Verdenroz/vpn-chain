use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tokio::process::Command;
use uuid::Uuid;

/// libbox in the Android app, and the desktop's sing-box.
pub const SING_BOX: [&str; 2] = ["1.13.14", "1.14.3"];
/// The server name the REALITY inbound borrows, served by the lab's own destination.
pub const SNI: &str = "dest.test";

const XRAY: &str = "xray-v26.3.27";
const BASE_IMAGE: &str =
    "debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f";
const DEST_IMAGE: &str =
    "caddy:2.10-alpine@sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d";

/// The docker side of one live test: a REALITY destination, a place to put xray's config, and
/// the xray and sing-box containers, all on the host network and all removed on drop.
pub struct Lab {
    pub vless_port: u16,
    pub api_port: u16,
    pub dest_port: u16,
    pub public_key: String,
    pub private_key: String,
    prefix: String,
    dir: PathBuf,
    clients: AtomicUsize,
    containers: Mutex<Vec<String>>,
}

impl Lab {
    /// Starts the destination. `tmp` is a scratch directory the lab may fill.
    pub async fn start(name: &str, tmp: &Path) -> Self {
        let prefix = format!("relay-live-{}-{name}", std::process::id());
        let dir = tmp.join(&prefix);
        std::fs::create_dir_all(&dir).unwrap();
        let (private_key, public_key) = keypair().await;
        let lab = Self {
            vless_port: free_port(),
            api_port: free_port(),
            dest_port: free_port(),
            public_key,
            private_key,
            prefix,
            dir,
            clients: AtomicUsize::new(0),
            containers: Mutex::default(),
        };
        std::fs::write(lab.dir.join("Caddyfile"), caddyfile(lab.dest_port)).unwrap();
        lab.run(
            "dest",
            DEST_IMAGE,
            &[],
            &["caddy", "run", "--config", "/etc/relay/Caddyfile"],
        )
        .await;
        wait_for_port(lab.dest_port).await;
        lab
    }

    /// The directory xray reads `xray.json` from, mounted read-only into its container.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Where xray reads its config from.
    pub fn config_path(&self) -> PathBuf {
        self.dir.join("xray.json")
    }

    /// The pinned xray on the host, for `xray -test`.
    pub fn xray_binary() -> PathBuf {
        bins().join(XRAY).join("xray")
    }

    /// Writes `config` and starts xray on it.
    pub async fn launch_xray(&self, config: &Value) {
        std::fs::write(self.config_path(), config.to_string()).unwrap();
        self.start_xray().await;
    }

    /// Starts xray on the config already in place.
    pub async fn start_xray(&self) {
        let xray = format!("/opt/bins/{XRAY}/xray");
        self.run(
            "xray",
            BASE_IMAGE,
            &["--tmpfs", "/var/log/xray"],
            &[&xray, "run", "-c", "/etc/relay/xray.json"],
        )
        .await;
    }

    /// Starts a sing-box `version` client for the given credentials; returns its mixed inbound
    /// port.
    pub async fn client(&self, uuid: &str, short_id: &str, version: &str) -> u16 {
        let port = free_port();
        let number = self.clients.fetch_add(1, Ordering::SeqCst);
        let file = format!("client-{number}.json");
        let config = self.client_config(uuid, short_id, port);
        std::fs::write(self.dir.join(&file), config.to_string()).unwrap();
        let binary = format!("/opt/bins/sing-box-{version}/sing-box");
        let config = format!("/etc/relay/{file}");
        self.run(
            &format!("client{number}"),
            BASE_IMAGE,
            &[],
            &[&binary, "run", "-c", &config],
        )
        .await;
        wait_for_port(port).await;
        port
    }

    pub fn xray_container(&self) -> String {
        format!("{}-xray", self.prefix)
    }

    /// The host PID and start time of the xray process.
    pub async fn xray_process(&self) -> String {
        docker(&[
            "inspect",
            "-f",
            "{{.State.Pid}} {{.State.StartedAt}}",
            &self.xray_container(),
        ])
        .await
    }

    pub async fn restart_xray(&self) {
        docker(&["restart", "-t", "1", &self.xray_container()]).await;
    }

    async fn run(&self, name: &str, image: &str, options: &[&str], command: &[&str]) {
        let container = format!("{}-{name}", self.prefix);
        let bins = format!("{}:/opt/bins:ro", bins().display());
        let config = format!("{}:/etc/relay:ro", self.dir.display());
        let args = [
            &[
                "run",
                "-d",
                "--rm",
                "--name",
                &container,
                "--network",
                "host",
            ][..],
            options,
            &["-v", &bins, "-v", &config, image],
            command,
        ]
        .concat();
        self.containers.lock().unwrap().push(container.clone());
        docker(&args).await;
    }

    /// The VLESS outbound core/config's SingBoxConfigFactory renders for the SOCKS proxy.
    fn client_config(&self, uuid: &str, short_id: &str, port: u16) -> Value {
        json!({
            "log": { "level": "warn" },
            "inbounds": [{ "type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": port }],
            "outbounds": [{
                "type": "vless", "tag": "vless-proxy",
                "server": "127.0.0.1", "server_port": self.vless_port,
                "uuid": uuid,
                "flow": "xtls-rprx-vision",
                "network": "tcp",
                "tls": {
                    "enabled": true, "server_name": SNI,
                    "utls": { "enabled": true, "fingerprint": "chrome" },
                    "reality": { "enabled": true, "public_key": self.public_key, "short_id": short_id },
                },
                "packet_encoding": "xudp",
                "connect_timeout": "3s",
            }],
            "route": { "final": "vless-proxy" },
        })
    }
}

impl Drop for Lab {
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
        let script = Path::new(env!("CARGO_MANIFEST_DIR")).join("fetch-bins.sh");
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

pub fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

pub fn short_id() -> String {
    Uuid::new_v4().simple().to_string()[..16].to_owned()
}

pub async fn wait_for_port(port: u16) {
    let deadline = Instant::now() + Duration::from_secs(15);
    while tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .is_err()
    {
        assert!(Instant::now() < deadline, "nothing listened on {port}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}
