use std::collections::HashMap;

use crate::Error;

type Keys = HashMap<String, Vec<String>>;

/// Checks the `wg-quick` file for a device's entry hop with the rules and the words of the
/// app's `WireGuardConfParser`. A refusal names the field, never its value.
pub fn validate(text: &str) -> Result<(), Error> {
    let (interface, peers) = sections(text);
    let [peer] = peers.as_slice() else {
        return Err(refuse(match peers.len() {
            0 => "Config has no peer.".to_owned(),
            count => format!("Config has {count} peers. Use one."),
        }));
    };
    key(&interface, "PrivateKey")?;
    if list(&interface, "Address").is_empty() {
        return Err(missing("Address"));
    }
    key(peer, "PublicKey")?;
    let allowed = list(peer, "AllowedIPs");
    if allowed.is_empty() {
        return Err(missing("AllowedIPs"));
    }
    if !routes_everything(&allowed) {
        return Err(refuse(
            "Only full-tunnel configs work. Set AllowedIPs to 0.0.0.0/0.",
        ));
    }
    endpoint(&single(peer, "Endpoint").ok_or_else(|| missing("Endpoint"))?)?;
    if let Some(preshared) = single(peer, "PresharedKey") {
        check_key("PresharedKey", &preshared)?;
    }
    if let Some(mtu) = single(&interface, "MTU") {
        number("MTU", &mtu, 576)?;
    }
    if let Some(keepalive) = single(peer, "PersistentKeepalive") {
        number("PersistentKeepalive", &keepalive, 0)?;
    }
    Ok(())
}

/// The keys of `[Interface]` and of each `[Peer]`, by lowercase name and in file order.
fn sections(text: &str) -> (Keys, Vec<Keys>) {
    let mut interface = Keys::new();
    let mut peers: Vec<Keys> = Vec::new();
    let mut current: Option<Option<usize>> = None;
    for line in text.lines() {
        let line = line.split('#').next().unwrap_or_default().trim();
        if line.eq_ignore_ascii_case("[Interface]") {
            current = Some(None);
        } else if line.eq_ignore_ascii_case("[Peer]") {
            peers.push(Keys::new());
            current = Some(Some(peers.len() - 1));
        } else if let (Some(at), Some((name, value))) = (current, line.split_once('=')) {
            let keys = match at {
                None => &mut interface,
                Some(index) => &mut peers[index],
            };
            keys.entry(name.trim().to_ascii_lowercase())
                .or_default()
                .push(value.trim().to_owned());
        }
    }
    (interface, peers)
}

fn single(keys: &Keys, name: &str) -> Option<String> {
    keys.get(&name.to_ascii_lowercase())?
        .last()
        .filter(|value| !value.is_empty())
        .cloned()
}

/// Repeatable keys may also carry several comma-separated values.
fn list(keys: &Keys, name: &str) -> Vec<String> {
    keys.get(&name.to_ascii_lowercase())
        .into_iter()
        .flatten()
        .flat_map(|value| value.split(','))
        .map(|part| part.trim().to_owned())
        .filter(|part| !part.is_empty())
        .collect()
}

fn key(keys: &Keys, name: &str) -> Result<(), Error> {
    check_key(name, &single(keys, name).ok_or_else(|| missing(name))?)
}

fn check_key(name: &str, value: &str) -> Result<(), Error> {
    let body = value.strip_suffix('=').unwrap_or_default();
    if body.len() == 43
        && body
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/')
    {
        Ok(())
    } else {
        Err(bad(name))
    }
}

fn number(name: &str, value: &str, min: i32) -> Result<(), Error> {
    match value.parse::<i32>() {
        Ok(number) if number >= min => Ok(()),
        _ => Err(bad(name)),
    }
}

/// Either the default route, or the halves wg-quick configs use to leave the default route
/// alone.
fn routes_everything(allowed: &[String]) -> bool {
    let has = |route: &str| allowed.iter().any(|held| held == route);
    has("0.0.0.0/0") || (has("0.0.0.0/1") && has("128.0.0.0/1"))
}

/// The desktop kill switch can only exempt an IPv4 address, so a name or an IPv6 one is refused.
fn endpoint(value: &str) -> Result<(), Error> {
    let (host, port) = value.rsplit_once(':').unwrap_or((value, ""));
    let port = port
        .parse::<u32>()
        .ok()
        .filter(|port| (1..=65535).contains(port));
    if host.is_empty() || port.is_none() {
        return Err(bad("Endpoint"));
    }
    let octets: Vec<&str> = host.split('.').collect();
    let ipv4 = octets.len() == 4
        && octets.iter().all(|octet| {
            (1..=3).contains(&octet.len())
                && octet.bytes().all(|b| b.is_ascii_digit())
                && octet.parse::<u16>().is_ok_and(|octet| octet <= 255)
        });
    if ipv4 {
        Ok(())
    } else {
        Err(refuse("Use an IPv4 endpoint."))
    }
}

fn missing(name: &str) -> Error {
    refuse(format!("Missing {name}."))
}

fn bad(name: &str) -> Error {
    refuse(format!("Bad {name}."))
}

fn refuse(reason: impl Into<String>) -> Error {
    Error::invalid(reason)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::Path;

    use super::*;

    #[test]
    fn every_shared_fixture_gets_the_verdict_the_app_gives_it() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/entry");
        let table = fs::read_to_string(dir.join("expected.tsv")).unwrap();

        for line in table.lines() {
            let (name, verdict) = line.split_once('\t').unwrap();
            let text = fs::read_to_string(dir.join(format!("{name}.conf"))).unwrap();
            let got = validate(&text)
                .err()
                .map_or("ok".to_owned(), |e| e.to_string());
            assert_eq!(got, verdict, "{name}");
        }
        let files = fs::read_dir(&dir)
            .unwrap()
            .filter(|file| {
                file.as_ref()
                    .unwrap()
                    .path()
                    .extension()
                    .is_some_and(|e| e == "conf")
            })
            .count();
        assert_eq!(files, table.lines().count());
    }
}
