use std::fs::{self, OpenOptions, Permissions};
use std::io::{ErrorKind, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use serde::de::DeserializeOwned;

use crate::Error;

pub fn read_json<T: DeserializeOwned>(path: &Path) -> Result<T, Error> {
    let text = fs::read_to_string(path).map_err(|error| Error::at(path, error))?;
    serde_json::from_str(&text).map_err(|error| Error::at(path, error))
}

/// Writes `path` only if nothing is there yet.
pub fn create(path: &Path, contents: &[u8], mode: u32) -> Result<(), Error> {
    let pending = stage(path, contents, mode)?;
    // A hard link fails when the target exists, and a reader never sees it half written.
    let linked = fs::hard_link(&pending, path);
    fs::remove_file(&pending).map_err(|error| Error::at(&pending, error))?;
    match linked {
        Ok(()) => sync_parent(path),
        Err(error) if error.kind() == ErrorKind::AlreadyExists => {
            Err(Error::at(path, "already exists; refusing to overwrite it"))
        }
        Err(error) => Err(Error::at(path, error)),
    }
}

fn stage(path: &Path, contents: &[u8], mode: u32) -> Result<std::path::PathBuf, Error> {
    let pending = path.with_extension("tmp");
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
