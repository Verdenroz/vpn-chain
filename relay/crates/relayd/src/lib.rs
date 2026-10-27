//! The relay's resident agent: the single owner of what the VLESS+REALITY relay serves.
//!
//! **The state file is the source of truth; xray's `config.json` is rendered from it.** xray's
//! runtime API changes users only in memory, so something on the server has to own a durable
//! copy and write the config to match. Keeping that copy in relayd's own schema-versioned file,
//! rather than reading facts back out of `config.json`, lets it hold what xray has no place for:
//! per-device roles, traffic totals, last-seen times and a pool of unissued short IDs.
//!
//! Private keys sit in a separate 0600 secrets file, and every credential is a [`Secret`] whose
//! `Debug` hides it, so the state can be printed, logged and diffed. Device UUIDs and short IDs
//! stay in the state, which is 0600 too, because each belongs to one device entry.

mod error;
mod render;
mod secret;
mod state;
mod store;

pub use error::Error;
pub use render::{render, write_config};
pub use secret::Secret;
pub use state::{
    Api, Device, Exit, INBOUND_TAG, Inbound, Relay, Role, SCHEMA, Secrets, State, XRAY_VERSION,
    Xray, secrets_path, valid_name,
};
