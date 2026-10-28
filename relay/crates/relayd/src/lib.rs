//! The relay's resident agent: the single owner of what the VLESS+REALITY relay serves.
//!
//! The state file is the source of truth; xray's `config.json` is rendered from it. xray's
//! runtime API changes users only in memory, so relayd keeps a durable, schema-versioned copy
//! that also holds what xray has no place for: device roles, traffic totals, last-seen times
//! and a pool of unissued short IDs.
//!
//! Private keys sit in a separate 0600 secrets file, and every credential is a [`Secret`] whose
//! `Debug` hides it, so the state can be printed, logged and diffed.
//!
//! [`Manager`] changes devices without restarting xray where it can; only a revocation, which
//! has to cut open connections, restarts it. xray's traffic counters are folded into the state
//! before every change.
//!
//! [`import`] adopts a relay as `server/deploy-server.sh` and `server/xray-clients.sh` leave it,
//! and refuses one it could not render back the same. Traffic counted since the scripts last
//! folded xray's stats is not read.

mod adapters;
mod adopt;
pub mod agent;
mod clock;
mod config_file;
mod devices;
pub mod egress;
mod enroll;
mod error;
mod identity;
mod import;
pub mod install;
mod key;
pub mod layout;
mod live;
pub mod pairing;
mod render;
mod secret;
mod state;
mod store;
mod validate;

pub use adapters::{JobsRestart, Systemctl, XrayLive};
pub use adopt::{adopt, adopt_with};
pub use clock::now;
pub use config_file::{Placement, write_config};
pub use devices::{Manager, SPARES};
pub use enroll::{Enrollments, FIRST_ADMIN_TTL, TOKEN_TTL, Verdict};
pub use error::{Error, Kind};
pub use identity::Identity;
pub use import::import;
pub use key::{Key, add_key};
pub use live::{Live, Restart};
pub use render::render;
pub use secret::Secret;
pub use state::{
    AGENT_HOST, AGENT_PORT, Agent, Api, DEVICES_LOCK, Device, Exit, INBOUND_TAG, Inbound, Relay,
    Role, SCHEMA, SECRETS_SCHEMA, Secrets, State, XRAY_VERSION, Xray, secrets_path,
};
pub use validate::valid_name;
