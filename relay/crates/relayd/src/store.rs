use std::fs::{self, OpenOptions, Permissions};
use std::io::{ErrorKind, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::Error;

pub fn read_json<T: DeserializeOwned>(path: &Path) -> Result<T, Error> {
    read_json_if_present(path)?.ok_or_else(|| Error::at(path, "no such file"))
}

/// Like [`read_json`], with a missing file as `None`. Any other read error still fails.
pub fn read_json_if_present<T: DeserializeOwned>(path: &Path) -> Result<Option<T>, Error> {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(Error::at(path, error)),
    };
    serde_json::from_str(&text)
        .map(Some)
        .map_err(|error| Error::at(path, redacted(&error)))
}

/// serde's message with every quoted value blanked, since the value may be a key or UUID.
/// Field and variant names are quoted with backticks and survive.
pub fn redacted(error: &serde_json::Error) -> String {
    error
        .to_string()
        .split('"')
        .enumerate()
        .map(|(index, part)| if index % 2 == 1 { ".." } else { part })
        .collect::<Vec<_>>()
        .join("\"")
}

/// Pretty-printed with a trailing newline, as jq writes it.
pub fn to_json<T: Serialize>(value: &T) -> Vec<u8> {
    let mut json = serde_json::to_vec_pretty(value).expect("relayd's types serialize");
    json.push(b'\n');
    json
}

/// Replaces `path` so that a crash leaves either the old file or the new one.
pub fn replace(path: &Path, contents: &[u8], mode: u32) -> Result<(), Error> {
    let pending = stage(path, contents, mode)?;
    fs::rename(&pending, path).map_err(|error| Error::at(path, error))?;
    sync_parent(path)
}

/// Writes `path` only if nothing is there yet.
pub fn create(path: &Path, contents: &[u8], mode: u32) -> Result<(), Error> {
    let pending = stage(path, contents, mode)?;
    // A hard link fails when the target exists, and a reader never sees it half written.
    let linked = fs::hard_link(&pending, path);
    fs::remove_file(&pending).map_err(|error| Error::at(&pending, error))?;
    match linked {
        Ok(()) => sync_parent(path),
        Err(error) if error.kind() == ErrorKind::AlreadyExists => Err(exists(path)),
        Err(error) => Err(Error::at(path, error)),
    }
}

/// Fails if `path` exists, for a caller about to [`create`] several files together.
pub fn absent(path: &Path) -> Result<(), Error> {
    if path.exists() {
        Err(exists(path))
    } else {
        Ok(())
    }
}

fn exists(path: &Path) -> Error {
    Error::at(path, "already exists; refusing to overwrite it")
}

fn stage(path: &Path, contents: &[u8], mode: u32) -> Result<PathBuf, Error> {
    let name = path.file_name().expect("a file path has a name");
    let pending = path.with_file_name(format!(".{}.tmp", name.to_string_lossy()));
    let at = |error| Error::at(&pending, error);
    match fs::remove_file(&pending) {
        Err(error) if error.kind() != ErrorKind::NotFound => return Err(at(error)),
        _ => {}
    }
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&pending)
        .map_err(at)?;
    // Set after opening, since the umask narrows the mode given at creation.
    file.set_permissions(Permissions::from_mode(mode))
        .map_err(at)?;
    file.write_all(contents).map_err(at)?;
    file.sync_all().map_err(at)?;
    Ok(pending)
}

fn sync_parent(path: &Path) -> Result<(), Error> {
    let parent = match path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => Path::new("."),
    };
    fs::File::open(parent)
        .and_then(|dir| dir.sync_all())
        .map_err(|error| Error::at(parent, error))
}
