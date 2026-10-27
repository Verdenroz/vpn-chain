use std::fs;
use std::os::unix::fs::{MetadataExt, chown};
use std::path::{Path, PathBuf};
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::key::hex;
use crate::{Error, Role, Secret, store};

const SCHEMA: u32 = 1;
const TOKEN_BYTES: usize = 32;

/// How long a pairing link works, in seconds.
pub const TOKEN_TTL: u64 = 15 * 60;

/// How long the first admin device's link works, in seconds.
pub const FIRST_ADMIN_TTL: u64 = 24 * 60 * 60;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Pending {
    hash: String,
    device: String,
    role: Role,
    expires: u64,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Tokens {
    v: u32,
    pending: Vec<Pending>,
}

/// What presenting a token came to.
#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    /// The token was good and is now burned.
    Claimed { device: String, role: Role },
    /// No such token, or one already used.
    Unknown,
    /// The token was past its expiry, and is burned too.
    Expired,
}

/// The enroll tokens waiting to be claimed, in a 0600 file beside the state. Only the SHA-256
/// of a token is stored, so the file cannot be used to enroll.
pub struct Enrollments {
    path: PathBuf,
}

impl Enrollments {
    /// The store that belongs to the state file at `state`.
    pub fn beside(state: &Path) -> Self {
        Self {
            path: state.with_file_name("enroll.json"),
        }
    }

    /// A new token for `device` that works for `ttl` seconds, replacing any it still had. It is
    /// returned once and kept only as a hash.
    pub fn issue(
        &self,
        lock: &Path,
        lock_wait: Duration,
        device: &str,
        role: Role,
        now: u64,
        ttl: u64,
    ) -> Result<Secret, Error> {
        let mut bytes = [0u8; TOKEN_BYTES];
        getrandom::fill(&mut bytes).expect("the OS provides randomness");
        let token = URL_SAFE_NO_PAD.encode(bytes);
        let _held = store::lock_within(lock, lock_wait)?;
        let mut tokens = self.read()?;
        tokens
            .pending
            .retain(|held| held.device != device && held.expires >= now);
        tokens.pending.push(Pending {
            hash: digest(&token),
            device: device.to_owned(),
            role,
            expires: now + ttl,
        });
        self.write(&tokens)?;
        Ok(Secret::new(token))
    }

    /// Checks `token` and burns it.
    pub fn claim(
        &self,
        lock: &Path,
        lock_wait: Duration,
        token: &str,
        now: u64,
    ) -> Result<Verdict, Error> {
        let _held = store::lock_within(lock, lock_wait)?;
        let mut tokens = self.read()?;
        let hash = digest(token);
        let Some(found) = tokens.pending.iter().position(|held| held.hash == hash) else {
            return Ok(Verdict::Unknown);
        };
        let claimed = tokens.pending.remove(found);
        tokens.pending.retain(|held| held.expires >= now);
        self.write(&tokens)?;
        Ok(if claimed.expires < now {
            Verdict::Expired
        } else {
            Verdict::Claimed {
                device: claimed.device,
                role: claimed.role,
            }
        })
    }

    fn read(&self) -> Result<Tokens, Error> {
        match store::read_json_if_present::<Tokens>(&self.path)? {
            Some(tokens) if tokens.v == SCHEMA => Ok(tokens),
            Some(tokens) => Err(Error::at(
                &self.path,
                format!(
                    "written by schema v{}; this relayd reads v{SCHEMA}",
                    tokens.v
                ),
            )),
            None => Ok(Tokens {
                v: SCHEMA,
                pending: Vec::new(),
            }),
        }
    }

    /// The file belongs to whoever owns its directory, so `relayd pair` run as root leaves it
    /// readable by the agent.
    fn write(&self, tokens: &Tokens) -> Result<(), Error> {
        store::replace(&self.path, &store::to_json(tokens), 0o600)?;
        let dir = self.path.parent().expect("a store file has a directory");
        let owner = fs::metadata(dir).map_err(|error| Error::at(dir, error))?;
        chown(&self.path, Some(owner.uid()), Some(owner.gid()))
            .map_err(|error| Error::at(&self.path, error))
    }
}

fn digest(token: &str) -> String {
    hex(&Sha256::digest(token.as_bytes()))
}
