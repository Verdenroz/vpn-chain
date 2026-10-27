use std::fs;
use std::path::{Path, PathBuf};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::{Error, Secret, store, validate};

/// Bump whenever a persisted shape changes, and keep a migration from the version it leaves.
pub const SCHEMA: u32 = 1;

/// The xray release relayd renders for: `XRAY_VERSION` in `server/lib/pins.sh`, without the `v`.
pub const XRAY_VERSION: &str = "26.3.27";

pub const INBOUND_TAG: &str = "vless-reality-443";

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
            state: read_versioned(state)?,
            secrets: read_versioned(secrets)?,
        };
        relay.validate()?;
        Ok(relay)
    }

    /// Writes both files, refusing to replace either. Secrets go first, so a state file never
    /// exists without them, and are removed again if the state cannot be written.
    pub fn create(&self, state: &Path, secrets: &Path) -> Result<(), Error> {
        self.validate()?;
        store::absent(state)?;
        store::create(secrets, &store::to_json(&self.secrets), 0o600)?;
        store::create(state, &store::to_json(&self.state), 0o600).inspect_err(|_| {
            let _ = fs::remove_file(secrets);
        })
    }

    pub fn validate(&self) -> Result<(), Error> {
        validate::relay(self)
    }
}

fn read_versioned<T: DeserializeOwned>(path: &Path) -> Result<T, Error> {
    #[derive(Deserialize)]
    struct Envelope {
        v: Option<u64>,
    }
    let text = fs::read_to_string(path).map_err(|error| Error::at(path, error))?;
    match store::parse::<Envelope>(path, &text)?.v {
        Some(v) if v == u64::from(SCHEMA) => store::parse(path, &text),
        Some(other) => Err(Error::at(
            path,
            format!("written by schema v{other}; this relayd reads v{SCHEMA}"),
        )),
        None => Err(Error::at(path, "not a relayd file: no \"v\"")),
    }
}
