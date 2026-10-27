use std::collections::HashSet;

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};

use crate::{Device, Error, Exit, INBOUND_TAG, Relay, Secret, State, XRAY_VERSION};

const NAME_MAX: usize = 32;
const SHORT_ID_MAX: usize = 16;
const KEY_BYTES: usize = 32;

pub(crate) fn relay(relay: &Relay) -> Result<(), Error> {
    let state = &relay.state;
    if state.xray.version != XRAY_VERSION {
        return Err(Error::new(format!(
            "the state is for xray {}, but this relayd renders for xray {XRAY_VERSION}",
            state.xray.version
        )));
    }
    check_inbound(state)?;
    check_devices(&state.devices)?;
    check_management_keys(state)?;
    check_short_ids(state)?;
    check_keys(relay)
}

fn check_inbound(state: &State) -> Result<(), Error> {
    let inbound = &state.inbound;
    if inbound.tag != INBOUND_TAG {
        return Err(Error::new(format!(
            "the inbound tag must be {INBOUND_TAG}, the name the server scripts look for"
        )));
    }
    let names: HashSet<&str> = inbound.server_names.iter().map(String::as_str).collect();
    if names.is_empty() || names.contains("") || names.len() != inbound.server_names.len() {
        return Err(Error::new(
            "the inbound's server names are missing, empty or listed twice",
        ));
    }
    if !valid_dest(&inbound.dest) {
        return Err(Error::new(format!(
            "the inbound dest {} is not a host:port off this machine",
            inbound.dest
        )));
    }
    if inbound.port == 0 {
        return Err(Error::new("the inbound port must be 1 to 65535"));
    }
    if let Some(api) = &state.api
        && (api.port == 0 || api.port == inbound.port)
    {
        return Err(Error::new(
            "the stats API port must be 1 to 65535 and differ from the inbound port",
        ));
    }
    let taken = [Some(inbound.port), state.api.as_ref().map(|api| api.port)];
    if state.agent.port == 0 || taken.contains(&Some(state.agent.port)) {
        return Err(Error::new(
            "the agent port must be 1 to 65535 and differ from the inbound and stats API ports",
        ));
    }
    Ok(())
}

fn check_management_keys(state: &State) -> Result<(), Error> {
    let mut ids = HashSet::new();
    for key in &state.keys {
        key.check()?;
        if !ids.insert(key.id.as_str()) {
            return Err(Error::new(format!(
                "management key {} is listed twice",
                key.id
            )));
        }
        if !state.devices.iter().any(|device| device.name == key.device) {
            return Err(Error::new(format!(
                "management key {} belongs to a device that does not exist",
                key.id
            )));
        }
    }
    Ok(())
}

/// REALITY forwards unauthenticated visitors to `dest`, so it must name the borrowed site.
fn valid_dest(dest: &str) -> bool {
    let Some((host, port)) = dest.rsplit_once(':') else {
        return false;
    };
    let host = host.trim_start_matches('[').trim_end_matches(']');
    let loopback = host == "localhost" || host == "::1" || host.starts_with("127.");
    !host.is_empty() && !loopback && port.parse::<u16>().is_ok_and(|port| port != 0)
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
        if !valid_uuid(device.uuid.expose()) {
            return Err(Error::new(format!(
                "device {} has no canonical UUID",
                device.name
            )));
        }
        if !uuids.insert(device.uuid.expose().to_ascii_lowercase()) {
            return Err(Error::new(format!(
                "device {} shares its UUID with another device",
                device.name
            )));
        }
    }
    Ok(())
}

/// xray maps any other 1 to 30 byte id to a UUIDv5, which no client would expect.
fn valid_uuid(uuid: &str) -> bool {
    uuid.len() == 36
        && uuid.char_indices().all(|(index, c)| match index {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit(),
        })
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

fn valid_short_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= SHORT_ID_MAX
        && id.len().is_multiple_of(2)
        && id.chars().all(|c| c.is_ascii_hexdigit())
}

/// A direct exit may still have a key in secrets: an exit change writes the secrets and the
/// state separately, and either order leaves one of the two ahead of the other.
/// xray's `x25519` prints the REALITY key unpadded base64url; WireGuard keys are padded base64.
fn check_keys(relay: &Relay) -> Result<(), Error> {
    let secrets = &relay.secrets;
    if !decodes_to_key(&URL_SAFE_NO_PAD, &secrets.reality_private_key) {
        return Err(Error::new(
            "the REALITY private key is not 32 bytes of unpadded base64url",
        ));
    }
    match (&relay.state.exit, &secrets.exit_secret_key) {
        (Exit::WireGuard { .. }, None) => Err(Error::new(
            "the WireGuard exit has no private key in secrets",
        )),
        (Exit::WireGuard { .. }, Some(key)) if !decodes_to_key(&STANDARD, key) => Err(Error::new(
            "the WireGuard exit key is not 32 bytes of base64",
        )),
        _ => Ok(()),
    }
}

fn decodes_to_key(engine: &impl Engine, key: &Secret) -> bool {
    engine
        .decode(key.expose())
        .is_ok_and(|bytes| bytes.len() == KEY_BYTES)
}

/// Matches the device-name rule of `server/xray-clients.sh`: `^[a-z0-9][a-z0-9._-]{0,31}$`.
pub fn valid_name(name: &str) -> bool {
    let mut chars = name.chars();
    let lead = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit();
    name.len() <= NAME_MAX
        && chars.next().is_some_and(lead)
        && chars.all(|c| lead(c) || matches!(c, '.' | '_' | '-'))
}
