use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::{Error, Secret, store};

/// Bump whenever a persisted shape changes, and keep a migration from the version it leaves.
pub const SCHEMA: u32 = 1;

/// The xray release relayd renders for. Must match the version the server installs.
pub const XRAY_VERSION: &str = "26.3.27";

pub const INBOUND_TAG: &str = "vless-reality-443";

const NAME_MAX: usize = 32;
const SHORT_ID_MAX: usize = 16;

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
    /// exists without them.
    pub fn create(&self, state: &Path, secrets: &Path) -> Result<(), Error> {
        self.validate()?;
        store::absent(state)?;
        store::create(secrets, &to_json(&self.secrets), 0o600)?;
        store::create(state, &to_json(&self.state), 0o600)
    }

    pub fn validate(&self) -> Result<(), Error> {
        let state = &self.state;
        if state.inbound.server_names.is_empty() {
            return Err(Error::new("the inbound has no server names"));
        }
        check_devices(&state.devices)?;
        check_short_ids(state)?;
        match (&state.exit, &self.secrets.exit_secret_key) {
            (Exit::WireGuard { .. }, None) => Err(Error::new(
                "the WireGuard exit has no private key in secrets",
            )),
            (Exit::Direct, Some(_)) => Err(Error::new(
                "secrets hold an exit key but the exit is direct",
            )),
            _ => Ok(()),
        }
    }
}

fn read_versioned<T: serde::de::DeserializeOwned>(path: &Path) -> Result<T, Error> {
    #[derive(Deserialize)]
    struct Envelope {
        v: Option<u32>,
    }
    let Envelope { v } = store::read_json(path)?;
    match v {
        Some(SCHEMA) => store::read_json(path),
        Some(other) => Err(Error::at(
            path,
            format!("written by schema v{other}; this relayd reads v{SCHEMA}"),
        )),
        None => Err(Error::at(path, "not a relayd file: no \"v\"")),
    }
}

fn to_json<T: Serialize>(value: &T) -> Vec<u8> {
    let mut json = serde_json::to_vec_pretty(value).expect("state serializes");
    json.push(b'\n');
    json
}

fn check_devices(devices: &[Device]) -> Result<(), Error> {
    let mut names = HashSet::new();
    let mut uuids = HashSet::new();
    for device in devices {
        if !valid_name(&device.name) {
            return Err(Error::new(format!("invalid device name: {}", device.name)));
        }
        if !names.insert(device.name.as_str()) {
            return Err(Error::new(format!("device listed twice: {}", device.name)));
        }
        if device.uuid.expose().is_empty() || !uuids.insert(device.uuid.expose()) {
            return Err(Error::new(format!(
                "device {} has an empty or shared UUID",
                device.name
            )));
        }
    }
    Ok(())
}

/// xray refuses an empty short ID list, and each ID is up to 16 hex digits, in pairs.
fn check_short_ids(state: &State) -> Result<(), Error> {
    let held = state.devices.iter().map(|device| &device.short_id);
    let all: Vec<&Secret> = held.chain(&state.spare_short_ids).collect();
    if all.is_empty() {
        return Err(Error::new("no short IDs; xray refuses an empty list"));
    }
    if !all.iter().all(|id| valid_short_id(id.expose())) {
        return Err(Error::new(
            "a short ID is empty or not 2 to 16 hex digits in pairs",
        ));
    }
    let unique: HashSet<&str> = all.iter().map(|id| id.expose()).collect();
    if unique.len() != all.len() {
        return Err(Error::new("a short ID is listed twice"));
    }
    Ok(())
}

/// Matches the device-name rule of `server/xray-clients.sh`: `^[a-z0-9][a-z0-9._-]{0,31}$`.
pub fn valid_name(name: &str) -> bool {
    let mut chars = name.chars();
    let lead = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit();
    name.len() <= NAME_MAX
        && chars.next().is_some_and(lead)
        && chars.all(|c| lead(c) || matches!(c, '.' | '_' | '-'))
}

fn valid_short_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= SHORT_ID_MAX
        && id.len().is_multiple_of(2)
        && id.chars().all(|c| c.is_ascii_hexdigit())
}
