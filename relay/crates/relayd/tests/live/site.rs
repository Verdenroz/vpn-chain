use std::fs;
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use live_harness::{ECHO_HOST, Lab, SNI, echo_server, roundtrip_to, short_id, wait_for_port};
use relayd::{
    Agent, Api, Device, Error, Exit, INBOUND_TAG, Inbound, Manager, Placement, Relay, Restart,
    Role, SCHEMA, SECRETS_SCHEMA, Secret, Secrets, State, XRAY_VERSION, Xray, XrayLive,
    secrets_path, write_config,
};
use serde_json::Value;
use tokio::runtime::{Builder, Runtime};
use uuid::Uuid;
use xray_api::XrayApi;

pub const SPARES: usize = 4;
pub const LAPTOP: &str = "laptop";
pub const NO_GROUP: &str = "relayd-test-no-such-group";

/// Restarts the lab's xray container, which is what `systemctl restart xray` does to the
/// relay: open connections drop, and xray reloads `config.json`.
pub struct DockerRestart {
    container: String,
    ports: [u16; 2],
}

impl Restart for DockerRestart {
    fn restart(&self) -> Result<(), Error> {
        let status = Command::new("docker")
            .args(["restart", "-t", "1", &self.container])
            .stdout(Stdio::null())
            .status()
            .map_err(|error| Error::new(error.to_string()))?;
        assert!(status.success(), "docker restart failed");
        let deadline = Instant::now() + Duration::from_secs(15);
        for port in self.ports {
            let addr = SocketAddr::from(([127, 0, 0, 1], port));
            while TcpStream::connect_timeout(&addr, Duration::from_millis(200)).is_err() {
                assert!(Instant::now() < deadline, "nothing listened on {port}");
                std::thread::sleep(Duration::from_millis(100));
            }
        }
        Ok(())
    }
}

/// A relay rendered by relayd and served by a real xray in docker, with the one admin
/// `laptop` and a pool of spare short IDs.
pub struct Site {
    pub rt: Runtime,
    pub lab: Lab,
    pub dir: tempfile::TempDir,
    pub state: PathBuf,
    pub secrets: PathBuf,
    pub config: PathBuf,
    pub lock: PathBuf,
    pub xray: PathBuf,
    pub echo: u16,
    pub laptop: Credentials,
    live: XrayLive,
    pub restart: DockerRestart,
}

#[derive(Clone)]
pub struct Credentials {
    pub uuid: String,
    pub short_id: String,
}

impl Site {
    pub fn start(name: &str) -> Self {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        let lab = rt.block_on(Lab::start(name, Path::new(env!("CARGO_TARGET_TMPDIR"))));
        let dir = tempfile::tempdir().unwrap();
        let state = dir.path().join("relay.json");
        let laptop = Credentials {
            uuid: Uuid::new_v4().to_string(),
            short_id: short_id(),
        };
        let relay = Relay {
            state: State {
                v: SCHEMA,
                xray: Xray {
                    version: XRAY_VERSION.to_owned(),
                },
                inbound: Inbound {
                    tag: INBOUND_TAG.to_owned(),
                    port: lab.vless_port,
                    server_names: vec![SNI.to_owned()],
                    // The lab's destination listens on loopback, which relayd refuses by name.
                    dest: format!("0.0.0.0:{}", lab.dest_port),
                },
                devices: vec![Device {
                    name: LAPTOP.to_owned(),
                    uuid: Secret::new(&laptop.uuid),
                    short_id: Secret::new(&laptop.short_id),
                    role: Role::Admin,
                    added: relayd::now(),
                    up: 0,
                    down: 0,
                    seen: None,
                }],
                spare_short_ids: (0..SPARES).map(|_| Secret::new(short_id())).collect(),
                exit: Exit::Direct,
                api: Some(Api { port: lab.api_port }),
                agent: Agent {
                    port: lab.agent_port,
                },
                keys: Vec::new(),
            },
            secrets: Secrets {
                v: SECRETS_SCHEMA,
                reality_private_key: Secret::new(&lab.private_key),
                exit_secret_key: None,
            },
        };
        let secrets = secrets_path(&state);
        relay.create(&state, &secrets).unwrap();
        let placement = Placement {
            group: NO_GROUP,
            swap: None,
        };
        write_config(&relay, &lab.config_path(), &placement).unwrap();
        rt.block_on(lab.start_xray());
        rt.block_on(wait_for_port(lab.api_port));
        rt.block_on(wait_for_port(lab.vless_port));
        let echo = rt.block_on(echo_server());
        Self {
            live: XrayLive::connect(lab.api_port).unwrap(),
            restart: DockerRestart {
                container: lab.xray_container(),
                ports: [lab.api_port, lab.vless_port],
            },
            config: lab.config_path(),
            lock: dir.path().join("devices.lock"),
            xray: lab.xray_test_command(),
            secrets,
            state,
            echo,
            laptop,
            dir,
            lab,
            rt,
        }
    }

    /// Another restart of the lab's xray, for a service that owns its own.
    pub fn restart_handle(&self) -> DockerRestart {
        DockerRestart {
            container: self.lab.xray_container(),
            ports: [self.lab.api_port, self.lab.vless_port],
        }
    }

    pub fn manager(&self) -> Manager<'_> {
        self.manager_restarting(&self.restart)
    }

    pub fn manager_restarting<'a>(&'a self, restart: &'a dyn Restart) -> Manager<'a> {
        Manager {
            state: &self.state,
            secrets: &self.secrets,
            config: &self.config,
            lock: &self.lock,
            placement: Placement {
                group: NO_GROUP,
                swap: Some(&self.xray),
            },
            live: &self.live,
            restart,
            spares: SPARES,
            lock_wait: Duration::from_secs(5),
            now: relayd::now,
        }
    }

    pub fn relay(&self) -> Relay {
        Relay::load(&self.state, &self.secrets).unwrap()
    }

    pub fn config(&self) -> String {
        fs::read_to_string(&self.config).unwrap()
    }

    pub fn identity(&self, out: &Path) -> Credentials {
        let identity: Value = serde_json::from_str(&fs::read_to_string(out).unwrap()).unwrap();
        Credentials {
            uuid: identity["uuid"].as_str().unwrap().to_owned(),
            short_id: identity["shortId"].as_str().unwrap().to_owned(),
        }
    }

    /// A path for an identity file the next operation will create.
    pub fn out(&self, name: &str) -> PathBuf {
        self.dir.path().join(format!("{name}.identity.json"))
    }

    pub fn client(&self, credentials: &Credentials, version: &str) -> u16 {
        self.rt.block_on(
            self.lab
                .client(&credentials.uuid, &credentials.short_id, version),
        )
    }

    pub fn works(&self, proxy: u16) -> bool {
        self.rt
            .block_on(roundtrip_to(proxy, ECHO_HOST, self.echo, b"ping"))
            .is_ok()
    }

    pub fn push(&self, proxy: u16, bytes: usize) {
        let payload = vec![7; bytes];
        self.rt
            .block_on(roundtrip_to(proxy, ECHO_HOST, self.echo, &payload))
            .unwrap();
    }

    pub fn process(&self) -> String {
        self.rt.block_on(self.lab.xray_process())
    }

    /// The emails xray itself holds on the inbound.
    pub fn users(&self) -> Vec<String> {
        let api = self.rt.block_on(async {
            XrayApi::new(
                SocketAddr::from(([127, 0, 0, 1], self.lab.api_port)),
                Duration::from_secs(3),
            )
        });
        let mut emails: Vec<String> = self
            .rt
            .block_on(api.users(INBOUND_TAG))
            .unwrap()
            .into_iter()
            .map(|user| user.email)
            .collect();
        emails.sort();
        emails
    }
}
