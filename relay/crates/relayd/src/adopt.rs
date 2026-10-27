use std::path::Path;

use serde_json::Value;

use crate::import::{import_held, same_config};
use crate::{Error, Placement, Relay, render, store, write_config};

/// Imports the relay `config` describes into `state` and `secrets`, then renders the config
/// back over itself through the checked swap, so it serves what device operations need.
///
/// Returns whether xray must restart for that: it keeps running the config it loaded until it
/// does, and the rendered one differs from the one it replaced whenever the relay lacked
/// anything relayd adds.
pub fn adopt(
    config: &Path,
    devices: &Path,
    state: &Path,
    secrets: &Path,
    lock: &Path,
    admin: Option<&str>,
    placement: &Placement,
) -> Result<bool, Error> {
    adopt_with(
        config,
        devices,
        state,
        secrets,
        lock,
        placement,
        |relay| match admin {
            Some(name) => relay.with_admin(name),
            None => Ok(relay),
        },
    )
}

/// [`adopt`], letting `shape` change the imported relay before it is rendered and saved.
pub fn adopt_with(
    config: &Path,
    devices: &Path,
    state: &Path,
    secrets: &Path,
    lock: &Path,
    placement: &Placement,
    shape: impl FnOnce(Relay) -> Result<Relay, Error>,
) -> Result<bool, Error> {
    let _held = store::lock(lock)?;
    let loaded: Value = store::read_json(config)?;
    let relay = shape(import_held(config, devices)?)?;
    store::absent(state)?;
    write_config(&relay, config, placement)?;
    relay.create(state, secrets)?;
    Ok(!same_config(&loaded, &render(&relay)))
}
