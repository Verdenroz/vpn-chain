use std::path::Path;

use serde_json::{Value, json};

use crate::{Error, Exit, Relay, store};

pub(crate) const API_TAG: &str = "api";
pub(crate) const EXIT_TAG: &str = "exit-wg";
const DIRECT_TAG: &str = "direct";
const BLOCK_TAG: &str = "block";

/// The xray config for `relay`, in the shape `server/deploy-server.sh` writes.
pub fn render(relay: &Relay) -> Value {
    let mut config = json!({
        "log": {
            "access": "none",
            "error": "/var/log/xray/error.log",
            "loglevel": "warning",
            "maskAddress": "half",
        },
    });
    if relay.state.api.is_some() {
        config["stats"] = json!({});
        config["api"] = json!({ "tag": API_TAG, "services": ["StatsService"] });
        config["policy"] = json!({
            "levels": { "0": { "statsUserUplink": true, "statsUserDownlink": true } },
        });
    }
    config["inbounds"] = inbounds(relay);
    config["outbounds"] = outbounds(relay);
    config["routing"] = routing(relay);
    config
}

/// Writes the rendered config so that a crash leaves either the old file or the new one.
pub fn write_config(relay: &Relay, path: &Path) -> Result<(), Error> {
    // xray's service user is not root and must be able to read it.
    store::replace(path, &store::to_json(&render(relay)), 0o644)
}

fn inbounds(relay: &Relay) -> Value {
    let state = &relay.state;
    let clients: Vec<Value> = state
        .devices
        .iter()
        .map(|device| {
            json!({
                "id": device.uuid.expose(),
                "flow": "xtls-rprx-vision",
                "email": device.name,
            })
        })
        .collect();
    let short_ids: Vec<&str> = state
        .devices
        .iter()
        .map(|device| device.short_id.expose())
        .chain(state.spare_short_ids.iter().map(|id| id.expose()))
        .collect();
    let vless = json!({
        "tag": state.inbound.tag,
        "listen": "0.0.0.0",
        "port": state.inbound.port,
        "protocol": "vless",
        "settings": { "clients": clients, "decryption": "none" },
        "streamSettings": {
            "network": "tcp",
            "security": "reality",
            "realitySettings": {
                "show": false,
                "dest": state.inbound.dest,
                "xver": 0,
                "serverNames": state.inbound.server_names,
                "privateKey": relay.secrets.reality_private_key.expose(),
                "shortIds": short_ids,
            },
        },
        "sniffing": {
            "enabled": true,
            "destOverride": ["http", "tls", "quic"],
            "routeOnly": true,
        },
    });
    let api = state.api.as_ref().map(|api| {
        json!({
            "tag": API_TAG,
            "listen": "127.0.0.1",
            "port": api.port,
            "protocol": "dokodemo-door",
            "settings": { "address": "127.0.0.1" },
        })
    });
    Value::Array([vless].into_iter().chain(api).collect())
}

fn outbounds(relay: &Relay) -> Value {
    let fixed = [
        json!({ "tag": DIRECT_TAG, "protocol": "freedom" }),
        json!({ "tag": BLOCK_TAG, "protocol": "blackhole" }),
    ];
    let exit = match &relay.state.exit {
        Exit::Direct => None,
        Exit::WireGuard {
            address,
            mtu,
            peer_public_key,
            endpoint,
            ..
        } => Some(json!({
            "tag": EXIT_TAG,
            "protocol": "wireguard",
            "settings": {
                "secretKey": exit_key(relay),
                "address": address,
                "mtu": mtu,
                "peers": [{
                    "publicKey": peer_public_key,
                    "endpoint": endpoint,
                    "allowedIPs": ["0.0.0.0/0", "::/0"],
                }],
            },
        })),
    };
    Value::Array(fixed.into_iter().chain(exit).collect())
}

fn exit_key(relay: &Relay) -> &str {
    relay
        .secrets
        .exit_secret_key
        .as_ref()
        .expect("a valid relay has a key for its WireGuard exit")
        .expose()
}

/// xray has no `final` outbound, so the exit is forced by a catch-all rule at the end.
fn routing(relay: &Relay) -> Value {
    let state = &relay.state;
    let api = state
        .api
        .as_ref()
        .map(|_| json!({ "type": "field", "inboundTag": [API_TAG], "outboundTag": API_TAG }));
    let (exit_dns, final_tag) = match &state.exit {
        Exit::Direct => (None, DIRECT_TAG),
        Exit::WireGuard { dns, .. } => (
            Some(json!({ "type": "field", "ip": [dns], "outboundTag": EXIT_TAG })),
            EXIT_TAG,
        ),
    };
    let rules: Vec<Value> = api
        .into_iter()
        .chain(exit_dns)
        .chain([
            json!({ "type": "field", "ip": ["geoip:private"], "outboundTag": BLOCK_TAG }),
            json!({ "type": "field", "network": "tcp,udp", "outboundTag": final_tag }),
        ])
        .collect();
    json!({ "domainStrategy": "AsIs", "rules": rules })
}
