use std::fs;
use std::path::{Path, PathBuf};

use super::host::{output, put, say, succeeds, systemctl, warn};
use crate::Error;

const DROP_IN: &str = "/etc/ssh/sshd_config.d/00-vpn-chain.conf";
const SSHD: &str = "/usr/sbin/sshd";
const RUN_DIR: &str = "/run/sshd";
const MAIN: &str = "/etc/ssh/sshd_config";
const PASSWD: &str = "/etc/passwd";

// The name sorts first because sshd keeps the first value it reads, and cloud images ship a
// later file that turns passwords on.
const SETTINGS: &str = "PasswordAuthentication no\nKbdInteractiveAuthentication no\n";

/// Turns password login off when someone could still log in with a key, and leaves the port
/// and every other setting alone. Without a key it only warns.
pub fn harden() -> Result<(), Error> {
    if !Path::new(SSHD).exists() {
        return Ok(());
    }
    if !has_authorized_key()? {
        warn(
            "no user who can log in has an authorized SSH key, so password login stays on; add one to ~/.ssh/authorized_keys and run `relayd install` again",
        );
        return Ok(());
    }
    if !reads_drop_ins()? {
        warn("sshd_config does not read /etc/ssh/sshd_config.d, so password login stays on");
        return Ok(());
    }
    // sshd -t and -T need the directory the service creates, and socket activation has not.
    fs::create_dir_all(RUN_DIR).map_err(|error| Error::at(Path::new(RUN_DIR), error))?;
    if put(Path::new(DROP_IN), SETTINGS.as_bytes(), 0o644)? {
        if !succeeds(SSHD, &["-t"]) {
            fs::remove_file(DROP_IN).map_err(|error| Error::at(Path::new(DROP_IN), error))?;
            warn("sshd rejected the key-only setting, so password login stays on");
            return Ok(());
        }
        reload();
    }
    let effective = output(SSHD, &["-T"])?;
    if effective
        .lines()
        .any(|line| line.eq_ignore_ascii_case("passwordauthentication no"))
    {
        say("SSH login is key-only; port 22 is unchanged");
    } else {
        warn("another sshd setting keeps password login on");
    }
    Ok(())
}

/// A unit that is not running reads the file when it starts, so only a running one is reloaded.
fn reload() {
    for unit in ["ssh", "sshd"] {
        if succeeds("systemctl", &["is-active", "--quiet", unit]) {
            if systemctl(&["reload", unit]).is_err() {
                warn(format!(
                    "could not reload {unit}; password login ends when it restarts"
                ));
            }
            return;
        }
    }
}

fn reads_drop_ins() -> Result<bool, Error> {
    let main = fs::read_to_string(MAIN).map_err(|error| Error::at(Path::new(MAIN), error))?;
    Ok(main.lines().any(|line| {
        let line = line.trim_start();
        line.starts_with("Include") && line.contains("sshd_config.d")
    }))
}

fn has_authorized_key() -> Result<bool, Error> {
    let passwd = fs::read_to_string(PASSWD).map_err(|error| Error::at(Path::new(PASSWD), error))?;
    Ok(passwd.lines().filter_map(login_home).any(|home| {
        fs::read_to_string(home.join(".ssh/authorized_keys")).is_ok_and(|keys| {
            keys.lines()
                .map(str::trim)
                .any(|line| !line.is_empty() && !line.starts_with('#'))
        })
    }))
}

/// The home of a root or ordinary account that has a shell to log in with.
fn login_home(line: &str) -> Option<PathBuf> {
    let [_, _, uid, _, _, home, shell] = line.split(':').collect::<Vec<_>>()[..] else {
        return None;
    };
    let ordinary = uid.parse::<u32>().is_ok_and(|uid| uid == 0 || uid >= 1000);
    let shell_works = !shell.ends_with("/nologin") && !shell.ends_with("/false");
    (ordinary && shell_works).then(|| PathBuf::from(home))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_root_and_ordinary_accounts_with_a_shell_count() {
        let home = |line| login_home(line).map(|home| home.display().to_string());

        assert_eq!(
            home("root:x:0:0:root:/root:/bin/bash"),
            Some("/root".to_owned())
        );
        assert_eq!(
            home("ana:x:1000:1000::/home/ana:/bin/zsh"),
            Some("/home/ana".to_owned())
        );
        assert_eq!(home("old:x:1001:1001::/home/old:/usr/sbin/nologin"), None);
        assert_eq!(home("svc:x:1002:1002::/srv:/bin/false"), None);
        assert_eq!(home("daemon:x:1:1:daemon:/usr/sbin:/bin/sh"), None);
    }
}
