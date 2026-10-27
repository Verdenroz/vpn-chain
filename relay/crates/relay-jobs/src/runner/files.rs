use std::fs;
use std::io::{ErrorKind, Write};
use std::os::unix::fs::{MetadataExt, PermissionsExt, fchown, symlink};
use std::path::{Path, PathBuf};

use crate::store::{self, create_private};
use crate::{Error, JobId, Layout, Version};

const CONFIG_LIMIT: u64 = 4 * 1024 * 1024;
const CURRENT_NEW: &str = ".current.new";

/// The xray version `current` points at; none when there is no link yet.
pub fn current_version(layout: &Layout) -> Result<Option<Version>, Error> {
    let target = match fs::read_link(layout.current()) {
        Ok(target) => target,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(Error::at(&layout.current(), error)),
    };
    target
        .to_str()
        .and_then(|target| target.parse().ok())
        .map(Some)
        .ok_or_else(|| Error::at(&layout.current(), "not a link to a version directory"))
}

/// Points `current` at `version` with a rename, so xray's unit never sees a missing link.
pub fn point_current(layout: &Layout, version: &Version) -> Result<(), Error> {
    let pending = layout.xray_root.join(CURRENT_NEW);
    match fs::remove_file(&pending) {
        Err(error) if error.kind() != ErrorKind::NotFound => {
            return Err(Error::at(&pending, error));
        }
        _ => {}
    }
    symlink(version.as_str(), &pending).map_err(|error| Error::at(&pending, error))?;
    fs::rename(&pending, layout.current()).map_err(|error| Error::at(&pending, error))
}

/// Whether `<root>/<version>/xray` is an executable file.
pub fn installed(layout: &Layout, version: &Version) -> bool {
    fs::metadata(layout.xray_binary(version))
        .is_ok_and(|meta| meta.is_file() && meta.permissions().mode() & 0o111 != 0)
}

/// The config the staged file holds. The unprivileged side wrote it.
pub fn read_staged(layout: &Layout) -> Result<Vec<u8>, Error> {
    store::read_regular(&layout.staged_config(), CONFIG_LIMIT)
}

/// Writes `bytes` beside the live config with the live config's owner and mode, not yet in
/// place. The config holds the REALITY private key, so it is 0600 until it has that owner.
pub fn stage_config(layout: &Layout, bytes: &[u8]) -> Result<PathBuf, Error> {
    let live = &layout.xray_config;
    let mut pending = live.clone().into_os_string();
    pending.push(".relay-jobs.new");
    let pending = PathBuf::from(pending);
    match fs::remove_file(&pending) {
        Err(error) if error.kind() != ErrorKind::NotFound => {
            return Err(Error::at(&pending, error));
        }
        _ => {}
    }
    let like = fs::metadata(live).ok();
    let mode = like.as_ref().map_or(0o600, |meta| meta.mode() & 0o777);
    let written = (|| {
        let mut file = create_private(&pending, 0o600)?;
        file.write_all(bytes)?;
        if let Some(meta) = &like {
            fchown(&file, Some(meta.uid()), Some(meta.gid()))?;
        }
        file.set_permissions(fs::Permissions::from_mode(mode))?;
        file.sync_all()
    })();
    written.map_err(|error| {
        let _ = fs::remove_file(&pending);
        Error::at(&pending, error)
    })?;
    Ok(pending)
}

pub fn place_config(layout: &Layout, pending: &Path) -> Result<(), Error> {
    fs::rename(pending, &layout.xray_config).map_err(|error| Error::at(pending, error))
}

/// What a job's rollback needs, kept root-only because the config holds a private key.
pub struct Snapshot {
    dir: PathBuf,
}

const SNAPSHOT_CONFIG: &str = "config";
const SNAPSHOT_VERSION: &str = "version";

impl Snapshot {
    pub fn take(layout: &Layout, id: &JobId) -> Result<Self, Error> {
        let dir = layout.snapshots().join(id.as_str());
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir(&dir).map_err(|error| Error::at(&dir, error))?;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))
            .map_err(|error| Error::at(&dir, error))?;
        match fs::read(&layout.xray_config) {
            Ok(config) => {
                let path = dir.join(SNAPSHOT_CONFIG);
                create_private(&path, 0o600)
                    .and_then(|mut file| file.write_all(&config))
                    .map_err(|error| Error::at(&path, error))?;
            }
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => return Err(Error::at(&layout.xray_config, error)),
        }
        if let Some(version) = current_version(layout)? {
            let path = dir.join(SNAPSHOT_VERSION);
            fs::write(&path, version.as_str()).map_err(|error| Error::at(&path, error))?;
        }
        Ok(Self { dir })
    }

    /// The snapshot a runner that died left for `id`, if any.
    pub fn find(layout: &Layout, id: &JobId) -> Option<Self> {
        let dir = layout.snapshots().join(id.as_str());
        dir.is_dir().then_some(Self { dir })
    }

    /// Puts back the config and the `current` link as they were when the snapshot was taken.
    pub fn restore(&self, layout: &Layout) -> Result<(), Error> {
        if let Ok(config) = fs::read(self.dir.join(SNAPSHOT_CONFIG)) {
            let pending = stage_config(layout, &config)?;
            place_config(layout, &pending)?;
        }
        if let Ok(version) = fs::read_to_string(self.dir.join(SNAPSHOT_VERSION)) {
            point_current(layout, &version.parse()?)?;
        }
        Ok(())
    }

    pub fn discard(self) {
        let _ = fs::remove_dir_all(self.dir);
    }
}
