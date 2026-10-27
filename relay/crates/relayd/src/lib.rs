//! The relay's resident agent: the single owner of what the VLESS+REALITY relay serves.
//!
//! The state file is the source of truth; xray's `config.json` is rendered from it. xray's
//! runtime API changes users only in memory, so something on the server has to own a durable
//! copy and write the config to match. Keeping that copy in relayd's own schema-versioned file,
//! rather than reading facts back out of `config.json`, lets it hold what xray has no place for:
//! per-device roles, traffic totals, last-seen times and a pool of unissued short IDs.
//!
//! Private keys sit in a separate 0600 secrets file, and every credential is a [`Secret`] whose
//! `Debug` hides it, so the state can be printed, logged and diffed. Device UUIDs and short IDs
//! stay in the state, which is 0600 too, because each belongs to one device entry.
//!
//! [`Manager`] changes devices without restarting xray where it can: an added, renamed or
//! rekeyed device goes in through xray's API and is also written to the state and the config,
//! and only a revocation, which has to cut open connections, restarts it. xray's traffic
//! counters are folded into the state before every change.
//!
//! [`import`] adopts a relay exactly as `server/deploy-server.sh` and `server/xray-clients.sh`
//! leave it, and refuses one it could not render back the same. It takes the traffic counters as
//! `devices.json` last recorded them: what xray counted since the scripts last folded its stats
//! is not read, and folding them before import is still to be built.

mod adapters;
mod adopt;
mod clock;
mod config_file;
mod devices;
mod error;
mod identity;
mod import;
mod key;
mod live;
mod render;
mod secret;
mod state;
mod store;
mod validate;

pub use adapters::{JobsRestart, Systemctl, XrayLive};
pub use adopt::adopt;
pub use clock::now;
pub use config_file::{Placement, write_config};
pub use devices::{Manager, SPARES};
pub use error::{Error, Kind};
pub use identity::Identity;
pub use import::import;
pub use key::Key;
pub use live::{Live, Restart};
pub use render::render;
pub use secret::Secret;
pub use state::{
    AGENT_HOST, AGENT_PORT, Agent, Api, DEVICES_LOCK, Device, Exit, INBOUND_TAG, Inbound, Relay,
    Role, SCHEMA, SECRETS_SCHEMA, Secrets, State, XRAY_VERSION, Xray, secrets_path,
};
pub use validate::valid_name;
