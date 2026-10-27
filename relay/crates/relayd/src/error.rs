use std::fmt;
use std::path::Path;

/// What kind of refusal an [`Error`] is, for a caller that must answer differently.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Invalid,
    Missing,
    Conflict,
    Failed,
}

/// A refusal or failure, worded for the person running relayd. It never carries a key, UUID or
/// short ID, so it is safe to print and log.
#[derive(Debug)]
pub struct Error {
    kind: Kind,
    reason: String,
}

impl Error {
    pub fn new(reason: impl Into<String>) -> Self {
        Self::of(Kind::Failed, reason)
    }

    /// The request names something relayd would never accept.
    pub fn invalid(reason: impl Into<String>) -> Self {
        Self::of(Kind::Invalid, reason)
    }

    /// The request names something that does not exist.
    pub fn missing(reason: impl Into<String>) -> Self {
        Self::of(Kind::Missing, reason)
    }

    /// The request is fine but the relay's state refuses it.
    pub fn conflict(reason: impl Into<String>) -> Self {
        Self::of(Kind::Conflict, reason)
    }

    pub fn at(path: &Path, reason: impl fmt::Display) -> Self {
        Self::new(format!("{}: {reason}", path.display()))
    }

    pub fn kind(&self) -> Kind {
        self.kind
    }

    fn of(kind: Kind, reason: impl Into<String>) -> Self {
        Self {
            kind,
            reason: reason.into(),
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.reason)
    }
}

impl std::error::Error for Error {}
