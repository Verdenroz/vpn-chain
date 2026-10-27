use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use crate::{Error, now};

const RECENT: usize = 200;

/// One line of the audit log. It names who did what to which device or job and how it ended,
/// and never a credential: devices appear by name, jobs by id.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Entry {
    pub time: String,
    /// The management key's id, or `-` when the request named none relayd holds.
    pub key: String,
    pub action: String,
    pub target: Option<String>,
    pub result: String,
}

/// An append-only JSON Lines file, 0600.
pub struct Audit {
    path: PathBuf,
    file: Mutex<File>,
}

impl Audit {
    pub fn open(path: &Path) -> Result<Self, Error> {
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .mode(0o600)
            .open(path)
            .map_err(|error| Error::at(path, error))?;
        Ok(Self {
            path: path.to_owned(),
            file: Mutex::new(file),
        })
    }

    /// Appends the entry. A log that cannot be written is reported on stderr and does not fail
    /// the request, which has already happened.
    pub fn record(&self, key: &str, action: &str, target: Option<&str>, result: &str) {
        let entry = Entry {
            time: now(),
            key: key.to_owned(),
            action: action.to_owned(),
            target: target.map(str::to_owned),
            result: result.to_owned(),
        };
        let mut line = serde_json::to_vec(&entry).expect("an entry serializes");
        line.push(b'\n');
        let mut file = self
            .file
            .lock()
            .expect("no holder of the audit file panics");
        if let Err(error) = file.write_all(&line) {
            eprintln!("relayd: {}", Error::at(&self.path, error));
        }
    }

    /// The last entries, oldest first.
    pub fn recent(&self) -> Result<Vec<Entry>, Error> {
        let text = fs::read_to_string(&self.path).map_err(|error| Error::at(&self.path, error))?;
        let lines: Vec<&str> = text.lines().collect();
        lines[lines.len().saturating_sub(RECENT)..]
            .iter()
            .map(|line| {
                serde_json::from_str(line)
                    .map_err(|_| Error::at(&self.path, "a line that is not an audit entry"))
            })
            .collect()
    }
}
