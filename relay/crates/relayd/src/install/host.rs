use std::fmt::Display;
use std::fs;
use std::os::unix::fs::chown;
use std::path::Path;
use std::process::Command;

use crate::{Error, store};

/// Progress goes to stderr, so stdout holds only what the person must keep.
pub fn say(message: impl Display) {
    eprintln!("relayd: {message}");
}

pub fn warn(message: impl Display) {
    eprintln!("relayd: warning: {message}");
}

/// Runs `program` and returns its stdout. A failure names the program and its first argument
/// and the last line it printed to stderr, never its stdout, which can hold a key.
pub fn output(program: &str, args: &[&str]) -> Result<String, Error> {
    capture(program, args, false)
}

/// Like [`run`], for a program such as `xray -test` that explains a failure on stdout.
pub fn run_reporting(program: &str, args: &[&str]) -> Result<(), Error> {
    capture(program, args, true).map(drop)
}

fn capture(program: &str, args: &[&str], report_stdout: bool) -> Result<String, Error> {
    let run = Command::new(program)
        .args(args)
        .env("DEBIAN_FRONTEND", "noninteractive")
        .output()
        .map_err(|error| Error::new(format!("{program}: {error}")))?;
    if run.status.success() {
        return Ok(String::from_utf8_lossy(&run.stdout).into_owned());
    }
    let stderr = String::from_utf8_lossy(&run.stderr);
    let stdout = String::from_utf8_lossy(&run.stdout);
    let shown = if report_stdout { &stdout } else { &stderr };
    Err(Error::new(format!(
        "{program} {} failed ({}): {}",
        args.first().unwrap_or(&""),
        run.status,
        shown.lines().last().unwrap_or("no message")
    )))
}

pub fn run(program: &str, args: &[&str]) -> Result<(), Error> {
    output(program, args).map(drop)
}

pub fn succeeds(program: &str, args: &[&str]) -> bool {
    run(program, args).is_ok()
}

pub fn systemctl(args: &[&str]) -> Result<(), Error> {
    run("systemctl", args)
}

pub fn have(name: &str) -> bool {
    std::env::var_os("PATH")
        .is_some_and(|paths| std::env::split_paths(&paths).any(|dir| dir.join(name).is_file()))
}

/// Installs the packages whose command is missing, and does nothing when none is.
pub fn require(commands: &[(&str, &str)]) -> Result<(), Error> {
    let missing: Vec<&str> = commands
        .iter()
        .filter(|(command, _)| !have(command))
        .map(|(_, package)| *package)
        .collect();
    if missing.is_empty() {
        return Ok(());
    }
    say(format!("installing {}", missing.join(", ")));
    run("apt-get", &["update", "-qq"])?;
    let mut args = vec!["install", "-y", "-qq", "--no-install-recommends"];
    args.extend(missing);
    run("apt-get", &args)
}

/// Writes `contents` to `path` at `mode` unless it is there already. Returns whether it wrote.
pub fn put(path: &Path, contents: &[u8], mode: u32) -> Result<bool, Error> {
    let current = fs::read(path).ok();
    let same_mode = fs::metadata(path).is_ok_and(|meta| {
        std::os::unix::fs::PermissionsExt::mode(&meta.permissions()) & 0o777 == mode
    });
    if current.as_deref() == Some(contents) && same_mode {
        return Ok(false);
    }
    let dir = path.parent().expect("an installed file has a directory");
    fs::create_dir_all(dir).map_err(|error| Error::at(dir, error))?;
    store::replace(path, contents, mode).map(|()| true)
}

/// The numeric id on the line for `name` in `/etc/passwd` or `/etc/group`.
pub fn id_of(file: &str, name: &str) -> Result<u32, Error> {
    let text = fs::read_to_string(file).map_err(|error| Error::at(Path::new(file), error))?;
    text.lines()
        .find_map(|line| {
            let mut fields = line.split(':');
            (fields.next() == Some(name))
                .then(|| fields.nth(1)?.parse().ok())
                .flatten()
        })
        .ok_or_else(|| Error::new(format!("no {name} in {file}")))
}

pub fn uid(user: &str) -> Result<u32, Error> {
    id_of("/etc/passwd", user)
}

pub fn gid(group: &str) -> Result<u32, Error> {
    id_of("/etc/group", group)
}

pub fn give(path: &Path, uid: u32, gid: u32) -> Result<(), Error> {
    chown(path, Some(uid), Some(gid)).map_err(|error| Error::at(path, error))
}

pub fn give_all(dir: &Path, uid: u32, gid: u32) -> Result<(), Error> {
    give(dir, uid, gid)?;
    for entry in fs::read_dir(dir).map_err(|error| Error::at(dir, error))? {
        give(
            &entry.map_err(|error| Error::at(dir, error))?.path(),
            uid,
            gid,
        )?;
    }
    Ok(())
}
