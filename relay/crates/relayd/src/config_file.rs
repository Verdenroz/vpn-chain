use std::fs;
use std::io::ErrorKind;
use std::os::unix::fs::{PermissionsExt, chown};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::{Error, Relay, render, store};

const GROUPS: &str = "/etc/group";

/// How [`write_config`] puts the config in place.
pub struct Placement<'a> {
    /// The group xray runs as. It gets read access; a missing group leaves the file 0600.
    pub group: &'a str,
    /// The xray binary, to replace an existing config only after `xray -test` accepts the new one.
    /// Without it, an existing file is never replaced.
    pub swap: Option<&'a Path>,
}

/// Writes the rendered config to `path`, so that a crash leaves either the old file or the new.
///
/// With [`Placement::swap`], the new config is staged beside `path` and checked with
/// `xray -test`, and the old one is kept as `<path>.bak.<unix seconds>` before the rename.
/// Restarting xray, and rolling back when it does not come up, is left to the caller.
pub fn write_config(relay: &Relay, path: &Path, placement: &Placement) -> Result<(), Error> {
    let pending = store::stage(path, &store::to_json(&render(relay)), 0o600)?;
    let placed = share_with_group(&pending, placement.group).and_then(|()| match placement.swap {
        None => store::install_new(&pending, path),
        Some(xray) => {
            check(xray, &pending)?;
            keep_previous(path)?;
            store::install(&pending, path)
        }
    });
    if placed.is_err() {
        let _ = fs::remove_file(&pending);
    }
    placed
}

/// The config holds the REALITY private key, so only xray's group may read it, never everyone.
fn share_with_group(pending: &Path, group: &str) -> Result<(), Error> {
    let Some(gid) = gid(group)? else {
        return Ok(());
    };
    chown(pending, None, Some(gid)).map_err(|error| Error::at(pending, error))?;
    fs::set_permissions(pending, fs::Permissions::from_mode(0o640))
        .map_err(|error| Error::at(pending, error))
}

fn gid(group: &str) -> Result<Option<u32>, Error> {
    let groups = fs::read_to_string(GROUPS).map_err(|error| Error::at(Path::new(GROUPS), error))?;
    Ok(groups.lines().find_map(|line| {
        let mut fields = line.split(':');
        (fields.next() == Some(group))
            .then(|| fields.nth(1)?.parse().ok())
            .flatten()
    }))
}

fn check(xray: &Path, pending: &Path) -> Result<(), Error> {
    let output = Command::new(xray)
        .arg("-test")
        .arg("-config")
        .arg(pending)
        .output()
        .map_err(|error| Error::at(xray, error))?;
    if output.status.success() {
        return Ok(());
    }
    Err(Error::at(
        xray,
        format!(
            "-test rejected the rendered config ({}); nothing was replaced",
            output.status
        ),
    ))
}

fn keep_previous(path: &Path) -> Result<(), Error> {
    if !path.exists() {
        return Ok(());
    }
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("the clock is past 1970")
        .as_secs();
    let mut taken = 0;
    loop {
        let mut backup = PathBuf::from(path).into_os_string();
        backup.push(format!(".bak.{seconds}"));
        if taken > 0 {
            backup.push(format!(".{taken}"));
        }
        match fs::hard_link(path, &backup) {
            Err(error) if error.kind() == ErrorKind::AlreadyExists => taken += 1,
            linked => return linked.map_err(|error| Error::at(Path::new(&backup), error)),
        }
    }
}
