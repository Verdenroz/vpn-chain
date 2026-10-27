use std::path::Path;

use serde::Serialize;

use crate::{Device, Error, Relay, Secret, store};

const SCHEMA: u32 = 1;

/// What a device needs to dial the relay besides the REALITY public key, which the relay's
/// admin already holds. It serializes with its credentials in the clear, so it goes only to the
/// admin who asked: a 0600 file they name, or the response to their own request.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    v: u32,
    name: String,
    uuid: Secret,
    short_id: Secret,
    port: u16,
    server_names: Vec<String>,
}

impl Identity {
    pub(crate) fn of(relay: &Relay, device: &Device) -> Self {
        Self {
            v: SCHEMA,
            name: device.name.clone(),
            uuid: device.uuid.clone(),
            short_id: device.short_id.clone(),
            port: relay.state.inbound.port,
            server_names: relay.state.inbound.server_names.clone(),
        }
    }

    /// Refuses to replace a file, so a device's identity is never overwritten by another's.
    pub(crate) fn create(&self, path: &Path) -> Result<(), Error> {
        store::create(path, &store::to_json(self), 0o600)
    }
}
