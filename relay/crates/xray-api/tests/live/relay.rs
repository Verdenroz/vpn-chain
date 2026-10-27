use std::net::SocketAddr;
use std::path::Path;
use std::time::{Duration, Instant};

use live_harness::{Lab, SNI, short_id};
use serde_json::{Value, json};
use uuid::Uuid;
use xray_api::{UserId, VlessUser, XrayApi};

pub use live_harness::SING_BOX;

pub const INBOUND: &str = "vless-reality-443";

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
    lab: Lab,
}

impl Relay {
    pub async fn start(name: &str) -> Self {
        let lab = Lab::start(name, Path::new(env!("CARGO_TARGET_TMPDIR"))).await;
        let relay = Self {
            api: XrayApi::new(
                SocketAddr::from(([127, 0, 0, 1], lab.api_port)),
                Duration::from_secs(3),
            ),
            seed: Device::new("seed", &short_id()),
            pool: (0..POOL).map(|_| short_id()).collect(),
            lab,
        };
        relay.lab.launch_xray(&relay.xray_config()).await;
        relay.wait_for_api().await;
        relay
    }

    /// Starts a sing-box `version` client for `device`; returns its mixed inbound port.
    pub async fn client(&self, device: &Device, version: &str) -> u16 {
        let uuid = device.user.id.expose().to_string();
        self.lab.client(&uuid, &device.short_id, version).await
    }

    /// The host PID and start time of the xray process.
    pub async fn xray_process(&self) -> String {
        self.lab.xray_process().await
    }

    pub async fn restart_xray(&self) {
        self.lab.restart_xray().await;
        self.wait_for_api().await;
    }

    async fn wait_for_api(&self) {
        let deadline = Instant::now() + Duration::from_secs(15);
        while self.api.users(INBOUND).await.is_err() {
            assert!(Instant::now() < deadline, "xray API never came up");
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    }

    fn xray_config(&self) -> Value {
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
                "tag": INBOUND, "listen": "127.0.0.1", "port": self.lab.vless_port, "protocol": "vless",
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
                        "show": false, "dest": format!("127.0.0.1:{}", self.lab.dest_port), "xver": 0,
                        "serverNames": [SNI], "privateKey": self.lab.private_key, "shortIds": short_ids,
                    },
                },
                "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true },
            }, {
                "tag": "api", "listen": "127.0.0.1", "port": self.lab.api_port, "protocol": "dokodemo-door",
                "settings": { "address": "127.0.0.1" },
            }],
            "outbounds": [{ "tag": "direct", "protocol": "freedom" }],
            "routing": { "rules": [
                { "type": "field", "inboundTag": ["api"], "outboundTag": "api" },
                { "type": "field", "network": "tcp,udp", "outboundTag": "direct" },
            ] },
        })
    }
}
