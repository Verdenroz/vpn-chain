use std::fs::{self, File, OpenOptions, Permissions, TryLockError};
use std::io::{self, ErrorKind, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::error::Category;

use crate::Error;

pub fn read_json<T: DeserializeOwned>(path: &Path) -> Result<T, Error> {
    read_json_if_present(path)?.ok_or_else(|| Error::at(path, "no such file"))
}

/// Like [`read_json`], with a missing file as `None`. Any other read error still fails.
pub fn read_json_if_present<T: DeserializeOwned>(path: &Path) -> Result<Option<T>, Error> {
    match fs::read_to_string(path) {
        Ok(text) => parse(path, &text).map(Some),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(None),
        Err(error) => Err(Error::at(path, error)),
    }
}

pub fn parse<T: DeserializeOwned>(path: &Path, text: &str) -> Result<T, Error> {
    serde_json::from_str(text).map_err(|error| Error::at(path, described(&error)))
}

/// What went wrong and where, without serde's message, which quotes the rejected value.
pub fn described(error: &serde_json::Error) -> String {
    let reason = match error.classify() {
        Category::Syntax => "not valid JSON",
        Category::Eof => "JSON that ends early",
        Category::Data => "a missing, unknown or invalid value",
        Category::Io => "an unreadable value",
    };
    match error.line() {
        0 => reason.to_owned(),
        line => format!("{reason} at line {line} column {}", error.column()),
    }
}

/// Takes `path` with `flock`, as the server scripts do on fd 9, without waiting for it.
pub fn lock(path: &Path) -> Result<File, Error> {
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(path)
        .map_err(|error| Error::at(path, error))?;
    match file.try_lock() {
        Ok(()) => Ok(file),
        Err(TryLockError::WouldBlock) => Err(Error::at(
            path,
            "held by another tool editing the relay; run again once it finishes",
        )),
        Err(TryLockError::Error(error)) => Err(Error::at(path, error)),
    }
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
    install(&pending, path)
}

/// Writes `path` only if nothing is there yet.
pub fn create(path: &Path, contents: &[u8], mode: u32) -> Result<(), Error> {
    let pending = stage(path, contents, mode)?;
    install_new(&pending, path)
}

/// Moves a file from [`stage`] over `path`.
pub fn install(pending: &Path, path: &Path) -> Result<(), Error> {
    fs::rename(pending, path).map_err(|error| Error::at(path, error))?;
    sync_parent(path)
}

/// Moves a file from [`stage`] to `path`, refusing if something is there.
pub fn install_new(pending: &Path, path: &Path) -> Result<(), Error> {
    // A hard link fails when the target exists, and a reader never sees it half written.
    let linked = fs::hard_link(pending, path);
    fs::remove_file(pending).map_err(|error| Error::at(pending, error))?;
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

/// Writes `contents` to a hidden file beside `path`, synced and at `mode`.
pub fn stage(path: &Path, contents: &[u8], mode: u32) -> Result<PathBuf, Error> {
    let name = path.file_name().expect("a file path has a name");
    // A prefix keeps the extension: xray picks the config format from it.
    let pending = path.with_file_name(format!(".tmp-{}", name.to_string_lossy()));
    let at = |error| Error::at(&pending, error);
    match fs::remove_file(&pending) {
        Err(error) if error.kind() != ErrorKind::NotFound => return Err(at(error)),
        _ => {}
    }
    let mut file = open_new(&pending, mode).map_err(at)?;
    // The umask can only narrow the mode given at creation, so it is set again exactly.
    file.set_permissions(Permissions::from_mode(mode))
        .map_err(at)?;
    file.write_all(contents).map_err(at)?;
    file.sync_all().map_err(at)?;
    Ok(pending)
}

fn open_new(path: &Path, mode: u32) -> io::Result<File> {
    OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(mode)
        .open(path)
}

pub fn sync_parent(path: &Path) -> Result<(), Error> {
    let parent = match path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => Path::new("."),
    };
    fs::File::open(parent)
        .and_then(|dir| dir.sync_all())
        .map_err(|error| Error::at(parent, error))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_private_file_is_private_from_the_moment_it_exists() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("keys");

        let file = open_new(&path, 0o600).unwrap();

        assert_eq!(file.metadata().unwrap().permissions().mode() & 0o777, 0o600);
    }
}
