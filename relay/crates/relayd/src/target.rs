use std::path::PathBuf;

use relayd::{DEVICES_LOCK, SPARES, secrets_path};

use crate::args::Args;

pub const XRAY_GROUP: &str = "xray";
const TARGET_FLAGS: [&str; 7] = [
    "state", "secrets", "config", "lock", "xray", "group", "spares",
];

/// Where the relay lives, from the flags every command that changes it takes.
pub struct Target {
    pub state: PathBuf,
    pub secrets: PathBuf,
    pub config: PathBuf,
    pub lock: PathBuf,
    pub xray: PathBuf,
    pub group: String,
    pub spares: usize,
}

impl Target {
    pub fn from(args: &mut Args) -> Option<Self> {
        let state = args.path("state")?;
        let config = args.path("config")?;
        Some(Self {
            secrets: args.path("secrets").unwrap_or_else(|| secrets_path(&state)),
            config,
            lock: args
                .path("lock")
                .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK)),
            xray: args.path("xray").unwrap_or_else(|| PathBuf::from("xray")),
            group: args.value("group").unwrap_or_else(|| XRAY_GROUP.to_owned()),
            spares: match args.value("spares") {
                Some(spares) => spares.parse().ok()?,
                None => SPARES,
            },
            state,
        })
    }
}

/// The flags every such command takes, and `extra` ones of its own.
pub fn valued(extra: &[&'static str]) -> Vec<&'static str> {
    [extra, &TARGET_FLAGS].concat()
}
