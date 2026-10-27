use std::fs;
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use super::host::{output, run, say};
use crate::key::hex;
use crate::{Error, Secret, XRAY_VERSION};

/// The release assets and their SHA-256, from `server/lib/pins.sh`, which a test compares.
pub const RELEASES: [(&str, &str, &str); 2] = [
    (
        "x86_64",
        "Xray-linux-64",
        "23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae",
    ),
    (
        "aarch64",
        "Xray-linux-arm64-v8a",
        "4d30283ae614e3057f730f67cd088a42be6fdf91f8639d82cb69e48cde80413c",
    ),
];

const FILES: [&str; 3] = ["xray", "geoip.dat", "geosite.dat"];

/// Puts the pinned xray under `root/<version>/` and points `root/current` at it unless that
/// link exists. Returns the binary.
pub fn ensure(root: &Path) -> Result<PathBuf, Error> {
    let dir = root.join(XRAY_VERSION);
    let binary = dir.join("xray");
    if !binary.exists() {
        say(format!("downloading xray {XRAY_VERSION}"));
        download(root, &dir)?;
    }
    let current = root.join("current");
    if current.symlink_metadata().is_err() {
        symlink(XRAY_VERSION, &current).map_err(|error| Error::at(&current, error))?;
    }
    Ok(binary)
}

fn download(root: &Path, dir: &Path) -> Result<(), Error> {
    let arch = std::env::consts::ARCH;
    let (_, asset, sha256) = RELEASES
        .iter()
        .find(|(known, ..)| *known == arch)
        .ok_or_else(|| Error::new(format!("no xray build is pinned for {arch}")))?;
    let staging = root.join(format!(".{XRAY_VERSION}.tmp"));
    let _ = fs::remove_dir_all(&staging);
    fs::create_dir_all(&staging).map_err(|error| Error::at(&staging, error))?;
    let zip = staging.join("xray.zip");
    let url =
        format!("https://github.com/XTLS/Xray-core/releases/download/v{XRAY_VERSION}/{asset}.zip");
    let (zip_arg, staging_arg) = (path_text(&zip), path_text(&staging));
    run(
        "curl",
        &["-fsSL", "--proto", "=https", "-o", &zip_arg, &url],
    )?;
    let bytes = fs::read(&zip).map_err(|error| Error::at(&zip, error))?;
    if hex(&Sha256::digest(bytes)) != *sha256 {
        return Err(Error::new(
            "the xray download differs from the pinned sha256; refusing to install it",
        ));
    }
    let mut unzip = vec!["-oq", &zip_arg];
    unzip.extend(FILES);
    unzip.extend(["-d", &staging_arg]);
    run("unzip", &unzip)?;
    fs::remove_file(&zip).map_err(|error| Error::at(&zip, error))?;
    for file in FILES {
        let mode = if file == "xray" { 0o755 } else { 0o644 };
        let path = staging.join(file);
        fs::set_permissions(&path, fs::Permissions::from_mode(mode))
            .map_err(|error| Error::at(&path, error))?;
    }
    fs::rename(&staging, dir).map_err(|error| Error::at(dir, error))
}

fn path_text(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

/// A new REALITY key pair as `(private, public)`.
pub fn keypair(xray: &Path) -> Result<(Secret, String), Error> {
    x25519(xray, &[])
}

/// The public key for the REALITY private key `private`.
pub fn public_key(xray: &Path, private: &Secret) -> Result<String, Error> {
    x25519(xray, &["-i", private.expose()]).map(|(_, public)| public)
}

fn x25519(xray: &Path, extra: &[&str]) -> Result<(Secret, String), Error> {
    let program = path_text(xray);
    let mut args = vec!["x25519"];
    args.extend(extra);
    let printed = output(&program, &args)?;
    let field = |label: &str| {
        printed.lines().find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.contains(label).then(|| value.trim().to_owned())
        })
    };
    match (field("PrivateKey"), field("PublicKey")) {
        (Some(private), Some(public)) => Ok((Secret::new(private), public)),
        _ => Err(Error::new("cannot read the key pair xray printed")),
    }
}
