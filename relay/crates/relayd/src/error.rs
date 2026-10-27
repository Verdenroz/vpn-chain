use std::fmt;
use std::path::Path;

/// A refusal or failure, worded for the person running relayd. It never carries a key, UUID or
/// short ID, so it is safe to print and log.
#[derive(Debug)]
pub struct Error(String);

impl Error {
    pub fn new(reason: impl Into<String>) -> Self {
        Self(reason.into())
    }

    pub fn at(path: &Path, reason: impl fmt::Display) -> Self {
        Self(format!("{}: {reason}", path.display()))
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}
