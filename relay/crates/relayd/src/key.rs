use std::path::Path;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use p256::ecdsa::VerifyingKey;
use p256::pkcs8::{DecodePublicKey, EncodePublicKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{Error, Relay, Role, State, now, store};

const ID_BYTES: usize = 8;

/// A management key: the public half of a P-256 key a device holds, and what it may do. Its id
/// is the first bytes of the SHA-256 of the public key, so it names the key without being a
/// secret.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Key {
    pub id: String,
    pub device: String,
    pub role: Role,
    /// SubjectPublicKeyInfo DER in base64, which is what a JVM `PublicKey.getEncoded()` holds.
    pub public_key: String,
    pub added: String,
}

impl Key {
    /// A key for `device` from a PEM or DER SubjectPublicKeyInfo of a P-256 key.
    pub fn new(device: &str, role: Role, public_key: &[u8], added: String) -> Result<Self, Error> {
        let der = spki_der(public_key)?;
        Ok(Self {
            id: id_of(&der),
            device: device.to_owned(),
            role,
            public_key: STANDARD.encode(der),
            added,
        })
    }

    /// Whether the id and public key agree and the key is a point on P-256.
    pub(crate) fn check(&self) -> Result<(), Error> {
        let der = STANDARD.decode(&self.public_key).map_err(|_| self.bad())?;
        if id_of(&der) != self.id || VerifyingKey::from_public_key_der(&der).is_err() {
            return Err(self.bad());
        }
        Ok(())
    }

    pub(crate) fn verifying_key(&self) -> VerifyingKey {
        let der = STANDARD
            .decode(&self.public_key)
            .expect("a valid relay holds base64 keys");
        VerifyingKey::from_public_key_der(&der).expect("a valid relay holds P-256 keys")
    }

    fn bad(&self) -> Error {
        Error::new(format!(
            "management key {} is not a P-256 public key matching its id",
            self.id
        ))
    }
}

fn spki_der(public_key: &[u8]) -> Result<Vec<u8>, Error> {
    let parsed = match std::str::from_utf8(public_key) {
        Ok(pem) if pem.trim_start().starts_with("-----BEGIN") => {
            VerifyingKey::from_public_key_pem(pem)
        }
        _ => VerifyingKey::from_public_key_der(public_key),
    };
    let key = parsed.map_err(|_| Error::invalid("not a P-256 public key in PEM or DER"))?;
    let der = key
        .to_public_key_der()
        .expect("a P-256 key encodes as SubjectPublicKeyInfo");
    Ok(der.as_bytes().to_vec())
}

fn id_of(der: &[u8]) -> String {
    hex(&Sha256::digest(der)[..ID_BYTES])
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Lets the key `public_key` (PEM or DER) manage the relay as `role`, from the device `device`.
/// It takes the devices lock like every other change to the state, and fails if it stays held
/// for `lock_wait`.
pub fn add_key(
    state: &Path,
    secrets: &Path,
    lock: &Path,
    lock_wait: Duration,
    device: &str,
    role: Role,
    public_key: &[u8],
) -> Result<Key, Error> {
    let _held = store::lock_within(lock, lock_wait)?;
    let relay = Relay::load(state, secrets)?;
    if !relay.state.devices.iter().any(|held| held.name == device) {
        return Err(Error::missing(format!("no device named {device}")));
    }
    let key = Key::new(device, role, public_key, now())?;
    if relay.state.keys.iter().any(|held| held.id == key.id) {
        return Err(Error::conflict(format!(
            "management key {} is already added",
            key.id
        )));
    }
    let keys = relay
        .state
        .keys
        .iter()
        .cloned()
        .chain([key.clone()])
        .collect();
    Relay {
        state: State {
            keys,
            ..relay.state
        },
        secrets: relay.secrets,
    }
    .save_state(state)?;
    Ok(key)
}
