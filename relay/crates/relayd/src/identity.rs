use std::path::Path;

use serde::Serialize;

use crate::{Device, Error, Relay, Secret, store};

const SCHEMA: u32 = 1;

/// What a device needs to dial the relay besides the REALITY public key, which the relay's
/// admin already holds. Written 0600 to a path the admin names.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Identity<'a> {
    v: u32,
    name: &'a str,
    uuid: &'a Secret,
    short_id: &'a Secret,
    port: u16,
    server_names: &'a [String],
}

impl<'a> Identity<'a> {
    pub(crate) fn of(relay: &'a Relay, device: &'a Device) -> Self {
        Self {
            v: SCHEMA,
            name: &device.name,
            uuid: &device.uuid,
            short_id: &device.short_id,
            port: relay.state.inbound.port,
            server_names: &relay.state.inbound.server_names,
        }
    }

    /// Refuses to replace a file, so a device's identity is never overwritten by another's.
    pub(crate) fn create(&self, path: &Path) -> Result<(), Error> {
        store::create(path, &store::to_json(self), 0o600)
    }
}
