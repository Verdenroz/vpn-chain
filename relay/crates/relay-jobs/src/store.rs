use std::fs::{self, File, OpenOptions};
use std::io::{ErrorKind, Read, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, fchown};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::{Error, Job, JobId, Layout};

const JOB_LIMIT: u64 = 64 * 1024;

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("the clock is past 1970")
        .as_secs()
}

/// Reads a regular file without following a symlink or blocking on a FIFO, since the directories
/// the unprivileged side owns are read by root.
pub fn read_regular(path: &Path, limit: u64) -> Result<Vec<u8>, Error> {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
        .map_err(|error| Error::at(path, error))?;
    let meta = file.metadata().map_err(|error| Error::at(path, error))?;
    if !meta.is_file() || meta.len() > limit {
        return Err(Error::at(path, "not a regular file within the size limit"));
    }
    let mut bytes = Vec::new();
    file.take(limit)
        .read_to_end(&mut bytes)
        .map_err(|error| Error::at(path, error))?;
    Ok(bytes)
}

/// Writes `<dir>/<name>` through a dotted temporary and a rename, so a reader sees all of the
/// file or none of it. The result belongs to `owner` when given.
pub fn write_atomic(
    dir: &Path,
    name: &str,
    bytes: &[u8],
    mode: u32,
    owner: Option<(u32, u32)>,
) -> Result<(), Error> {
    let pending = dir.join(format!(".{name}.tmp"));
    match fs::remove_file(&pending) {
        Err(error) if error.kind() != ErrorKind::NotFound => {
            return Err(Error::at(&pending, error));
        }
        _ => {}
    }
    let written = (|| {
        let mut file = create_private(&pending, mode)?;
        if let Some((uid, gid)) = owner {
            fchown(&file, Some(uid), Some(gid))?;
        }
        file.write_all(bytes)
    })();
    written
        .and_then(|()| fs::rename(&pending, dir.join(name)))
        .map_err(|error| {
            let _ = fs::remove_file(&pending);
            Error::at(&dir.join(name), error)
        })
}

/// A new file at `path` that no symlink can redirect, with exactly `mode`.
pub fn create_private(path: &Path, mode: u32) -> std::io::Result<File> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .mode(mode)
        .open(path)?;
    file.set_permissions(std::os::unix::fs::PermissionsExt::from_mode(mode))?;
    Ok(file)
}

pub fn job_path(layout: &Layout, id: &JobId) -> std::path::PathBuf {
    layout.jobs().join(format!("{id}.json"))
}

pub fn read_job(layout: &Layout, id: &JobId) -> Result<Job, Error> {
    let path = job_path(layout, id);
    let bytes = read_regular(&path, JOB_LIMIT)?;
    let job: Job =
        serde_json::from_slice(&bytes).map_err(|_| Error::at(&path, "not a job record"))?;
    if &job.id == id {
        Ok(job)
    } else {
        Err(Error::at(&path, "the record names another job"))
    }
}

/// Stores the record 0600, owned by whoever owns the jobs directory so the unprivileged side
/// can read what the root runner writes.
pub fn write_job(layout: &Layout, job: &Job) -> Result<(), Error> {
    let dir = layout.jobs();
    let meta = fs::metadata(&dir).map_err(|error| Error::at(&dir, error))?;
    let mut json = serde_json::to_vec_pretty(job).expect("a job serializes");
    json.push(b'\n');
    write_atomic(
        &dir,
        &format!("{}.json", job.id),
        &json,
        0o600,
        Some((meta.uid(), meta.gid())),
    )
}
