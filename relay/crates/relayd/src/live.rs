use std::collections::BTreeMap;

use xray_api::{Traffic, VlessUser};

use crate::{Device, Error};

/// The running xray, reached through its API. Changes made here last only until xray restarts.
pub trait Live {
    fn add_user(&self, user: VlessUser) -> Result<(), xray_api::Error>;
    fn remove_user(&self, email: &str) -> Result<(), xray_api::Error>;
    /// Every user's traffic since the last read; xray zeroes the counters as it reads them.
    fn drain_traffic(&self) -> Result<BTreeMap<String, Traffic>, xray_api::Error>;
}

/// Restarts xray so it loads `config.json`, which also cuts every open connection.
pub trait Restart {
    fn restart(&self) -> Result<(), Error>;
}

/// `devices` with `traffic` added to their totals, and `seen` set to `now` for any that moved
/// bytes. Counters for a name no device holds are dropped.
pub(crate) fn fold(
    devices: &[Device],
    traffic: &BTreeMap<String, Traffic>,
    now: &str,
) -> Vec<Device> {
    devices
        .iter()
        .map(|device| match traffic.get(&device.name) {
            Some(moved) => Device {
                up: device.up + moved.up,
                down: device.down + moved.down,
                seen: if moved.up + moved.down > 0 {
                    Some(now.to_owned())
                } else {
                    device.seen.clone()
                },
                ..device.clone()
            },
            None => device.clone(),
        })
        .collect()
}
