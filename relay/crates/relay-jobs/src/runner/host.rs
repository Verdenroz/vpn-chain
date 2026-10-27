use std::io;
use std::path::Path;
use std::process::{Command, Stdio};

const SYSTEMCTL: &str = "/usr/bin/systemctl";
const XRAY_UNIT: &str = "xray.service";

/// What the runner does to the machine beyond files.
pub trait Host {
    fn restart_xray(&self) -> io::Result<()>;

    /// Whether `xray -test` accepts `config`.
    fn accepts_config(&self, xray: &Path, config: &Path) -> io::Result<bool>;
}

/// Fixed argv, no shell and an empty environment, with output discarded so a rejected config is
/// never echoed into the journal.
pub struct Systemd;

impl Host for Systemd {
    fn restart_xray(&self) -> io::Result<()> {
        let status = quiet(Command::new(SYSTEMCTL).args(["restart", XRAY_UNIT])).status()?;
        if status.success() {
            Ok(())
        } else {
            Err(io::Error::other(format!("systemctl restart: {status}")))
        }
    }

    fn accepts_config(&self, xray: &Path, config: &Path) -> io::Result<bool> {
        let status = quiet(Command::new(xray).arg("-test").arg("-config").arg(config)).status()?;
        Ok(status.success())
    }
}

fn quiet(command: &mut Command) -> &mut Command {
    command
        .env_clear()
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
}
