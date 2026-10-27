use std::collections::HashMap;
use std::fs;
use std::path::Path;

use crate::{Error, Exit, Secret};

const MTU: u32 = 1280;
const DNS: &str = "10.2.0.1";

/// The WireGuard exit a provider's `wg-quick` file describes, and its private key.
pub fn read(path: &Path) -> Result<(Exit, Secret), Error> {
    let text = fs::read_to_string(path).map_err(|error| Error::at(path, error))?;
    parse(&text).map_err(|error| Error::at(path, error))
}

/// [`read`] for the file's text. A refusal names the missing or bad field, never its value.
pub fn parse(text: &str) -> Result<(Exit, Secret), Error> {
    let fields = fields(text);
    let first = |name: &str| fields.get(name).and_then(|values| values.first().cloned());
    let need = |name: &str| {
        first(name).ok_or_else(|| Error::invalid(format!("no {name} in the WireGuard file")))
    };
    let address: Vec<String> = fields
        .get("address")
        .into_iter()
        .flatten()
        .flat_map(|line| line.split(','))
        .map(|part| part.trim().to_owned())
        .collect();
    if address.is_empty() {
        need("address")?;
    }
    let mtu = match first("mtu") {
        Some(mtu) => mtu
            .parse()
            .map_err(|_| Error::invalid("the WireGuard MTU is not a number"))?,
        None => MTU,
    };
    let dns = first("dns")
        .and_then(|dns| dns.split(',').next().map(|first| first.trim().to_owned()))
        .unwrap_or_else(|| DNS.to_owned());
    let exit = Exit::WireGuard {
        address,
        mtu,
        peer_public_key: need("publickey")?,
        endpoint: need("endpoint")?,
        dns,
    };
    Ok((exit, Secret::new(need("privatekey")?)))
}

/// The `Key = value` lines, by lowercase key and in file order, of `[Interface]` and the first
/// `[Peer]`. Anything after a `#` is a comment, as `wg-quick` reads it.
fn fields(text: &str) -> HashMap<String, Vec<String>> {
    let mut seen_peer = false;
    let mut section = "";
    let mut fields: HashMap<String, Vec<String>> = HashMap::new();
    for line in text.lines() {
        let line = line.split('#').next().unwrap_or_default().trim();
        if let Some(name) = line
            .strip_prefix('[')
            .and_then(|rest| rest.strip_suffix(']'))
        {
            section = if name.eq_ignore_ascii_case("peer") && !seen_peer {
                seen_peer = true;
                "peer"
            } else if name.eq_ignore_ascii_case("interface") {
                "interface"
            } else {
                ""
            };
        } else if let (false, Some((key, value))) = (section.is_empty(), line.split_once('=')) {
            fields
                .entry(key.trim().to_ascii_lowercase())
                .or_default()
                .push(value.trim().to_owned());
        }
    }
    fields
}

#[cfg(test)]
mod tests {
    use super::*;

    const PROTON: &str = "[Interface]\n# Bouncing = 3\nPrivateKey = kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n\n[Peer]\nPublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 203.0.113.9:51820\n";

    fn read_text(text: &str) -> Result<(Exit, Secret), Error> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("wg0.conf");
        fs::write(&path, text).unwrap();
        read(&path)
    }

    #[test]
    fn a_provider_file_becomes_the_exit_and_its_key() {
        let (exit, key) = read_text(PROTON).unwrap();

        assert_eq!(
            exit,
            Exit::WireGuard {
                address: vec!["10.2.0.2/32".to_owned()],
                mtu: 1280,
                peer_public_key: "pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".to_owned(),
                endpoint: "203.0.113.9:51820".to_owned(),
                dns: "10.2.0.1".to_owned(),
            }
        );
        assert_eq!(key.expose(), "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=");
    }

    #[test]
    fn comments_are_dropped_and_repeated_addresses_are_all_kept() {
        let text = PROTON
            .replace(
                "Address = 10.2.0.2/32",
                "Address = 10.2.0.2/32 # v4\nAddress = fd00::2/128",
            )
            .replace("DNS = 10.2.0.1", "DNS = 10.2.0.1, 10.2.0.3");

        let (exit, _) = read_text(&text).unwrap();

        let Exit::WireGuard { address, dns, .. } = exit else {
            panic!("not a WireGuard exit");
        };
        assert_eq!(address, ["10.2.0.2/32", "fd00::2/128"]);
        assert_eq!(dns, "10.2.0.1");
    }

    #[test]
    fn a_missing_field_is_named_and_the_error_holds_no_value() {
        let error = read_text(&PROTON.replace("Endpoint", "Endpont"))
            .unwrap_err()
            .to_string();

        assert!(error.contains("no endpoint"), "{error}");
        assert!(!error.contains("kPwG0r8"), "{error}");
    }
}
