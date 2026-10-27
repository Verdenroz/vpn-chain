use std::collections::{BTreeMap, HashSet};
use std::path::Path;

use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

use crate::render::{API_TAG, EXIT_TAG};
use crate::{
    Api, Device, Error, Exit, INBOUND_TAG, Inbound, Relay, Role, SCHEMA, Secret, Secrets, State,
    XRAY_VERSION, Xray, render, store,
};

/// One `devices.json` entry, as `server/xray-clients.sh` writes it.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Tracked {
    short_id: Secret,
    added: String,
    #[serde(default)]
    up: u64,
    #[serde(default)]
    down: u64,
    seen: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VlessInbound {
    port: u16,
    settings: VlessSettings,
    stream_settings: StreamSettings,
}

#[derive(Deserialize)]
struct VlessSettings {
    clients: Vec<Client>,
}

#[derive(Deserialize)]
struct Client {
    id: Secret,
    email: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StreamSettings {
    reality_settings: Reality,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Reality {
    dest: String,
    server_names: Vec<String>,
    private_key: Secret,
    short_ids: Vec<Secret>,
}

#[derive(Deserialize)]
struct ApiInbound {
    port: u16,
}

#[derive(Deserialize)]
struct WireGuardOutbound {
    settings: WireGuardSettings,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireGuardSettings {
    secret_key: Secret,
    address: Vec<String>,
    mtu: u32,
    peers: Vec<Peer>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Peer {
    public_key: String,
    endpoint: String,
}

/// Adopts the relay that `config` and its `devices` sidecar describe, keeping every key,
/// short ID and traffic counter.
///
/// It refuses what `xray-clients.sh` refuses to edit (a client missing from `devices.json`, an
/// empty short ID list), and any config it could not render back the same, so nothing the relay
/// serves today is dropped. Every device comes in as a member: none has a management key yet.
///
/// Both files are read under `lock`, the lock the server scripts hold while they edit them, and
/// import refuses to run while another tool holds it.
pub fn import(config: &Path, devices: &Path, lock: &Path) -> Result<Relay, Error> {
    let _held = store::lock(lock)?;
    let tracked: BTreeMap<String, Tracked> =
        store::read_json_if_present(devices)?.unwrap_or_default();
    let config_json: Value = store::read_json(config)?;
    adopt(&config_json, tracked).map_err(|error| Error::at(config, error))
}

fn adopt(config: &Value, mut tracked: BTreeMap<String, Tracked>) -> Result<Relay, Error> {
    let inbound: VlessInbound = section(config, "inbounds", INBOUND_TAG)?
        .ok_or_else(|| Error::new(format!("no {INBOUND_TAG} inbound")))?;
    let clients = inbound.settings.clients;
    let reality = inbound.stream_settings.reality_settings;
    if reality.short_ids.is_empty() || reality.short_ids.iter().any(|id| id.expose().is_empty()) {
        return Err(Error::new("short IDs missing or empty"));
    }
    refuse_untracked(&clients, &tracked)?;

    let devices = clients
        .into_iter()
        .map(|client| {
            let entry = tracked.remove(&client.email).ok_or_else(|| {
                Error::new(format!(
                    "client {} is listed twice in the config",
                    client.email
                ))
            })?;
            Ok(Device {
                name: client.email,
                uuid: client.id,
                short_id: entry.short_id,
                role: Role::Member,
                added: entry.added,
                up: entry.up,
                down: entry.down,
                seen: entry.seen,
            })
        })
        .collect::<Result<Vec<Device>, Error>>()?;
    if !tracked.is_empty() {
        let names: Vec<&str> = tracked.keys().map(String::as_str).collect();
        return Err(Error::new(format!(
            "devices.json lists devices with no client in the config ({})",
            names.join(" ")
        )));
    }

    let offered: HashSet<&str> = reality.short_ids.iter().map(Secret::expose).collect();
    if let Some(device) = devices
        .iter()
        .find(|device| !offered.contains(device.short_id.expose()))
    {
        return Err(Error::new(format!(
            "the short ID devices.json records for {} is not in the config",
            device.name
        )));
    }
    let held: HashSet<&str> = devices
        .iter()
        .map(|device| device.short_id.expose())
        .collect();
    let spare_short_ids = reality
        .short_ids
        .iter()
        .filter(|id| !held.contains(id.expose()))
        .cloned()
        .collect();

    let api = section::<ApiInbound>(config, "inbounds", API_TAG)?.map(|api| Api { port: api.port });
    let (exit, exit_secret_key) = exit(config)?;
    let relay = Relay {
        state: State {
            v: SCHEMA,
            xray: Xray {
                version: XRAY_VERSION.to_owned(),
            },
            inbound: Inbound {
                tag: INBOUND_TAG.to_owned(),
                port: inbound.port,
                server_names: reality.server_names,
                dest: reality.dest,
            },
            devices,
            spare_short_ids,
            exit,
            api,
        },
        secrets: Secrets {
            v: SCHEMA,
            reality_private_key: reality.private_key,
            exit_secret_key,
        },
    };
    relay.validate()?;
    refuse_unmodelled(config, &relay)?;
    Ok(relay)
}

fn refuse_untracked(clients: &[Client], tracked: &BTreeMap<String, Tracked>) -> Result<(), Error> {
    let untracked: Vec<&str> = clients
        .iter()
        .map(|client| client.email.as_str())
        .filter(|name| !tracked.contains_key(*name))
        .collect();
    if untracked.is_empty() {
        return Ok(());
    }
    Err(Error::new(format!(
        "clients not in devices.json ({}); run: devices.sh adopt NAME",
        untracked.join(" ")
    )))
}

fn exit(config: &Value) -> Result<(Exit, Option<Secret>), Error> {
    let Some(outbound) = section::<WireGuardOutbound>(config, "outbounds", EXIT_TAG)? else {
        return Ok((Exit::Direct, None));
    };
    let settings = outbound.settings;
    let peer = settings
        .peers
        .into_iter()
        .next()
        .ok_or_else(|| Error::new(format!("the {EXIT_TAG} outbound has no peer")))?;
    let dns = config
        .pointer("/routing/rules")
        .and_then(Value::as_array)
        .and_then(|rules| {
            rules
                .iter()
                .find(|rule| rule["outboundTag"] == EXIT_TAG && rule.get("ip").is_some())
        })
        .and_then(|rule| rule["ip"][0].as_str())
        .ok_or_else(|| Error::new(format!("the {EXIT_TAG} outbound has no DNS rule")))?;
    let exit = Exit::WireGuard {
        address: settings.address,
        mtu: settings.mtu,
        peer_public_key: peer.public_key,
        endpoint: peer.endpoint,
        dns: dns.to_owned(),
    };
    Ok((exit, Some(settings.secret_key)))
}

fn section<T: DeserializeOwned>(config: &Value, list: &str, tag: &str) -> Result<Option<T>, Error> {
    let Some(entry) = config
        .get(list)
        .and_then(Value::as_array)
        .and_then(|entries| entries.iter().find(|entry| entry["tag"] == tag))
    else {
        return Ok(None);
    };
    T::deserialize(entry)
        .map(Some)
        .map_err(|error| Error::new(format!("the {tag} entry: {}", store::described(&error))))
}

fn refuse_unmodelled(config: &Value, relay: &Relay) -> Result<(), Error> {
    match difference(&normalize(config), &normalize(&render(relay)), "") {
        None => Ok(()),
        Some(path) => Err(Error::new(format!(
            "{path} is not what relayd renders; importing would change or drop it"
        ))),
    }
}

/// xray treats `shortIds` as a set, and relayd lists device IDs before spares.
fn normalize(config: &Value) -> Value {
    let mut config = config.clone();
    let ids = config
        .pointer_mut("/inbounds")
        .and_then(Value::as_array_mut)
        .and_then(|inbounds| {
            inbounds
                .iter_mut()
                .find(|inbound| inbound["tag"] == INBOUND_TAG)
        })
        .and_then(|inbound| inbound.pointer_mut("/streamSettings/realitySettings/shortIds"))
        .and_then(Value::as_array_mut);
    if let Some(ids) = ids {
        ids.sort_by(|a, b| a.as_str().cmp(&b.as_str()));
    }
    config
}

/// The JSON pointer of the first place `found` and `rendered` differ, ignoring key order. It
/// names a location and never a value, so it is safe to print.
fn difference(found: &Value, rendered: &Value, path: &str) -> Option<String> {
    match (found, rendered) {
        (Value::Object(found), Value::Object(rendered)) => {
            found.keys().chain(rendered.keys()).find_map(|key| {
                let here = format!("{path}/{key}");
                match (found.get(key), rendered.get(key)) {
                    (Some(found), Some(rendered)) => difference(found, rendered, &here),
                    _ => Some(here),
                }
            })
        }
        (Value::Array(found), Value::Array(rendered)) if found.len() == rendered.len() => found
            .iter()
            .zip(rendered)
            .enumerate()
            .find_map(|(index, (found, rendered))| {
                difference(found, rendered, &format!("{path}/{index}"))
            }),
        _ if found == rendered => None,
        _ => Some(if path.is_empty() {
            "/".to_owned()
        } else {
            path.to_owned()
        }),
    }
}
