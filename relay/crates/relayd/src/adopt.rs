use std::path::Path;

use serde_json::Value;

use crate::import::import_held;
use crate::{Error, Placement, store, write_config};

const HANDLER: &str = "HandlerService";

/// Imports the relay `config` describes into `state` and `secrets`, then renders the config
/// back over itself through the checked swap, so it serves what device operations need.
///
/// Returns whether xray must restart for that: a relay that served only `StatsService` keeps
/// running without `HandlerService` until it does.
pub fn adopt(
    config: &Path,
    devices: &Path,
    state: &Path,
    secrets: &Path,
    lock: &Path,
    admin: Option<&str>,
    placement: &Placement,
) -> Result<bool, Error> {
    let _held = store::lock(lock)?;
    let served = serves_handler(&store::read_json(config)?);
    let imported = import_held(config, devices)?;
    let relay = match admin {
        Some(name) => imported.with_admin(name)?,
        None => imported,
    };
    store::absent(state)?;
    write_config(&relay, config, placement)?;
    relay.create(state, secrets)?;
    Ok(!served)
}

fn serves_handler(config: &Value) -> bool {
    config
        .pointer("/api/services")
        .and_then(Value::as_array)
        .is_some_and(|services| services.iter().any(|service| service == HANDLER))
}
