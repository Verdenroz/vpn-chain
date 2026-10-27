use std::collections::HashMap;
use std::fmt;
use std::ops::RangeInclusive;
use std::sync::Mutex;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use p256::ecdsa::Signature;
use p256::ecdsa::signature::Verifier as _;
use sha2::{Digest, Sha256};

use crate::{Key, Role};

/// How far a request's timestamp may sit from the relay's clock, in seconds.
pub const WINDOW: u64 = 120;

/// Starts every signed message, so a signature made for this API is useless anywhere else.
pub const CONTEXT: &str = "vpn-chain-relay-v1";

const NONCE_LENGTH: RangeInclusive<usize> = 16..=64;
const REMEMBERED: usize = 8192;

/// The message a management key signs, as five lines after [`CONTEXT`]: the method, the request
/// target exactly as sent (path and query), the lowercase hex SHA-256 of the body, the timestamp
/// in unix seconds, and the nonce. The signature is ECDSA over P-256 and SHA-256, DER encoded.
pub fn message(method: &str, target: &str, body: &[u8], timestamp: &str, nonce: &str) -> String {
    let digest: String = Sha256::digest(body)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    format!("{CONTEXT}\n{method}\n{target}\n{digest}\n{timestamp}\n{nonce}")
}

/// A request as it arrived, with the four signing headers it carried.
pub struct Signed<'a> {
    pub method: &'a str,
    pub target: &'a str,
    pub body: &'a [u8],
    pub key: Option<&'a str>,
    pub timestamp: Option<&'a str>,
    pub nonce: Option<&'a str>,
    pub signature: Option<&'a str>,
}

/// Who a verified request came from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Principal {
    pub key: String,
    pub device: String,
    pub role: Role,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Why {
    Unsigned,
    UnknownKey,
    Stale,
    BadSignature,
    Replayed,
    Overloaded,
}

impl fmt::Display for Why {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Unsigned => "unsigned",
            Self::UnknownKey => "unknown key",
            Self::Stale => "outside the time window",
            Self::BadSignature => "bad signature",
            Self::Replayed => "replayed nonce",
            Self::Overloaded => "too many nonces held",
        })
    }
}

/// A refusal, with the key it named when relayd holds that key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reject {
    pub why: Why,
    pub key: Option<String>,
}

/// Checks signatures and remembers the nonces it has accepted for as long as their timestamps
/// could still pass, so a captured request cannot be sent twice.
pub struct Verifier {
    seen: Mutex<HashMap<(String, String), u64>>,
    capacity: usize,
}

impl Default for Verifier {
    fn default() -> Self {
        Self::with_capacity(REMEMBERED)
    }
}

impl Verifier {
    /// A verifier that refuses new requests while it holds `capacity` unexpired nonces.
    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            seen: Mutex::default(),
            capacity,
        }
    }

    pub fn verify(&self, keys: &[Key], request: &Signed, now: u64) -> Result<Principal, Reject> {
        let (Some(id), Some(timestamp), Some(nonce), Some(signature)) = (
            request.key,
            request.timestamp,
            request.nonce,
            request.signature,
        ) else {
            return Err(refuse(Why::Unsigned, None));
        };
        let Some(key) = keys.iter().find(|key| key.id == id) else {
            return Err(refuse(Why::UnknownKey, None));
        };
        let reject = |why| refuse(why, Some(key.id.clone()));
        let sent: u64 = timestamp.parse().map_err(|_| reject(Why::Stale))?;
        if sent.abs_diff(now) > WINDOW {
            return Err(reject(Why::Stale));
        }
        let signed = message(
            request.method,
            request.target,
            request.body,
            timestamp,
            nonce,
        );
        let valid = NONCE_LENGTH.contains(&nonce.len())
            && STANDARD
                .decode(signature)
                .ok()
                .and_then(|der| Signature::from_der(&der).ok())
                .is_some_and(|signature| {
                    key.verifying_key()
                        .verify(signed.as_bytes(), &signature)
                        .is_ok()
                });
        if !valid {
            return Err(reject(Why::BadSignature));
        }
        self.remember(&key.id, nonce, now).map_err(reject)?;
        Ok(Principal {
            key: key.id.clone(),
            device: key.device.clone(),
            role: key.role,
        })
    }

    /// A timestamp up to [`WINDOW`] ahead passes until it is [`WINDOW`] behind, so a nonce is
    /// held for twice the window.
    fn remember(&self, key: &str, nonce: &str, now: u64) -> Result<(), Why> {
        let mut seen = self
            .seen
            .lock()
            .expect("no holder of the nonce cache panics");
        seen.retain(|_, expires| *expires >= now);
        if seen.contains_key(&(key.to_owned(), nonce.to_owned())) {
            return Err(Why::Replayed);
        }
        if seen.len() >= self.capacity {
            return Err(Why::Overloaded);
        }
        seen.insert((key.to_owned(), nonce.to_owned()), now + 2 * WINDOW);
        Ok(())
    }
}

fn refuse(why: Why, key: Option<String>) -> Reject {
    Reject { why, key }
}
