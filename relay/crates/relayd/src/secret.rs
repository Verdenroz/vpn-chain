use std::fmt;

use serde::{Deserialize, Serialize};

/// A credential: the REALITY private key, a WireGuard key, a device UUID or short ID. Its
/// `Debug` never shows the value, so a state printed with `{:?}` stays safe to log.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Secret(String);

impl Secret {
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Secret(..)")
    }
}
