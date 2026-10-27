use std::fs;
use std::path::{Path, PathBuf};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::{Error, Key, Secret, store, validate};

/// Bump whenever the state's shape changes, and keep a migration from the version it leaves.
pub const SCHEMA: u32 = 2;

/// The secrets file's own version, which only changes when its own shape does.
pub const SECRETS_SCHEMA: u32 = 1;

/// The loopback port the agent listens on unless the relay's config already names another.
pub const AGENT_PORT: u16 = 10086;

/// The name a client dials to reach the agent through the tunnel. `.invalid` never resolves, so
/// it cannot be reached any other way.
pub const AGENT_HOST: &str = "agent.vpn-chain.invalid";

/// The xray release relayd renders for: `XRAY_VERSION` in `server/lib/pins.sh`, without the `v`.
pub const XRAY_VERSION: &str = "26.3.27";

pub const INBOUND_TAG: &str = "vless-reality-443";

/// The lock `server/lib/xray-common.sh` holds around every edit of the config and `devices.json`.
pub const DEVICES_LOCK: &str = "/run/lock/vpn-chain-devices.lock";

/// Everything relayd knows about the relay except its private keys, which live in [`Secrets`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct State {
    pub v: u32,
    pub xray: Xray,
    pub inbound: Inbound,
    /// In the order xray lists the clients.
    pub devices: Vec<Device>,
    /// Short IDs xray accepts that no device holds yet.
    pub spare_short_ids: Vec<Secret>,
    pub exit: Exit,
    /// The loopback stats API. A relay deployed before per-device traffic has none.
    pub api: Option<Api>,
    pub agent: Agent,
    /// Who may call the agent.
    pub keys: Vec<Key>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Xray {
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Inbound {
    pub tag: String,
    pub port: u16,
    pub server_names: Vec<String>,
    pub dest: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Device {
    pub name: String,
    pub uuid: Secret,
    pub short_id: Secret,
    pub role: Role,
    pub added: String,
    /// Bytes through the relay, folded from xray's counters, which reset on every read.
    pub up: u64,
    pub down: u64,
    pub seen: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Role {
    Admin,
    Member,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum Exit {
    /// The VPS's own address is the exit.
    Direct,
    /// A WireGuard tail (Proton), dialled by xray itself. Its private key is in [`Secrets`].
    #[serde(rename = "wireguard")]
    WireGuard {
        address: Vec<String>,
        mtu: u32,
        peer_public_key: String,
        endpoint: String,
        /// The provider's resolver, routed through the tunnel rather than blocked as private.
        dns: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Api {
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Agent {
    pub port: u16,
}

/// Kept in its own 0600 file so the state can be read, shown and diffed without a key in it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Secrets {
    pub v: u32,
    pub reality_private_key: Secret,
    pub exit_secret_key: Option<Secret>,
}

/// A state and its secrets, checked against each other.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Relay {
    pub state: State,
    pub secrets: Secrets,
}

/// Where the secrets of the state at `state` live unless told otherwise:
/// `relay.json` keeps them in `relay.secrets.json`.
pub fn secrets_path(state: &Path) -> PathBuf {
    state.with_extension("secrets.json")
}

impl Relay {
    pub fn load(state: &Path, secrets: &Path) -> Result<Self, Error> {
        let relay = Self {
            state: read_state(state)?,
            secrets: read_versioned(secrets, SECRETS_SCHEMA)?,
        };
        relay.validate()?;
        Ok(relay)
    }

    /// Writes both files, refusing to replace an existing state. Secrets go first, so a state
    /// never exists without them. A secrets file with no state beside it is what a crash
    /// between the two writes leaves, so it is replaced.
    pub fn create(&self, state: &Path, secrets: &Path) -> Result<(), Error> {
        self.validate()?;
        store::absent(state)?;
        store::replace(secrets, &store::to_json(&self.secrets), 0o600)?;
        store::create(state, &store::to_json(&self.state), 0o600)
    }

    /// The same relay with the device `name` an admin.
    pub fn with_admin(self, name: &str) -> Result<Self, Error> {
        if !self.state.devices.iter().any(|device| device.name == name) {
            return Err(Error::new(format!("no device named {name}")));
        }
        let devices = self
            .state
            .devices
            .into_iter()
            .map(|device| Device {
                role: if device.name == name {
                    Role::Admin
                } else {
                    device.role
                },
                ..device
            })
            .collect();
        Ok(Self {
            state: State {
                devices,
                ..self.state
            },
            secrets: self.secrets,
        })
    }

    /// Replaces the state file, leaving the secrets alone.
    pub fn save_state(&self, state: &Path) -> Result<(), Error> {
        self.validate()?;
        store::replace(state, &store::to_json(&self.state), 0o600)
    }

    pub fn validate(&self) -> Result<(), Error> {
        validate::relay(self)
    }
}

fn read_state(path: &Path) -> Result<State, Error> {
    let text = read_text(path)?;
    match version(path, &text)? {
        SCHEMA => store::parse(path, &text),
        1 => {
            let mut v1: Value = store::parse(path, &text)?;
            v1["v"] = json!(SCHEMA);
            v1["agent"] = json!({ "port": free_agent_port(&v1) });
            v1["keys"] = json!([]);
            serde_json::from_value(v1).map_err(|error| Error::at(path, store::described(&error)))
        }
        other => Err(unreadable(path, other, SCHEMA)),
    }
}

/// [`AGENT_PORT`], or the next port up that the inbound and the stats API do not hold.
fn free_agent_port(v1: &Value) -> u64 {
    let taken = [&v1["inbound"]["port"], &v1["api"]["port"]];
    (u64::from(AGENT_PORT)..)
        .find(|port| !taken.iter().any(|held| held.as_u64() == Some(*port)))
        .expect("the port space is not exhausted")
}

fn read_versioned<T: DeserializeOwned>(path: &Path, schema: u32) -> Result<T, Error> {
    let text = read_text(path)?;
    match version(path, &text)? {
        v if v == schema => store::parse(path, &text),
        other => Err(unreadable(path, other, schema)),
    }
}

fn read_text(path: &Path) -> Result<String, Error> {
    fs::read_to_string(path).map_err(|error| Error::at(path, error))
}

fn version(path: &Path, text: &str) -> Result<u32, Error> {
    #[derive(Deserialize)]
    struct Envelope {
        v: Option<u64>,
    }
    let v = store::parse::<Envelope>(path, text)?
        .v
        .ok_or_else(|| Error::at(path, "not a relayd file: no \"v\""))?;
    Ok(u32::try_from(v).unwrap_or(u32::MAX))
}

fn unreadable(path: &Path, found: u32, current: u32) -> Error {
    Error::at(
        path,
        format!("written by schema v{found}; this relayd reads v{current}"),
    )
}
