use std::path::PathBuf;
use std::time::Duration;

use crate::Version;

const STATE_DIR: &str = "/var/lib/vpn-chain";
const XRAY_ROOT: &str = "/opt/vpn-chain/xray";
const XRAY_CONFIG: &str = "/usr/local/etc/xray/config.json";
const CONFIRM_SECS: u64 = 300;
const POLL: Duration = Duration::from_millis(500);

/// Where jobs live on disk and how long a changed xray has to be confirmed.
///
/// The unprivileged side owns `jobs`, `requests`, `confirm` and `staged`; `snapshots` is root's.
#[derive(Clone, Debug)]
pub struct Layout {
    pub state: PathBuf,
    /// One directory per xray version, and the `current` symlink to the one that runs.
    pub xray_root: PathBuf,
    pub xray_config: PathBuf,
    pub confirm_window: Duration,
    pub poll: Duration,
}

impl Layout {
    pub fn new(state: impl Into<PathBuf>) -> Self {
        Self {
            state: state.into(),
            xray_root: XRAY_ROOT.into(),
            xray_config: XRAY_CONFIG.into(),
            confirm_window: Duration::from_secs(CONFIRM_SECS),
            poll: POLL,
        }
    }

    /// The defaults, each replaceable by a `RELAY_JOBS_*` variable.
    pub fn from_env() -> Self {
        let var = |name: &str| std::env::var_os(format!("RELAY_JOBS_{name}"));
        let mut layout = Self::new(var("STATE_DIR").unwrap_or_else(|| STATE_DIR.into()));
        if let Some(root) = var("XRAY_ROOT") {
            layout.xray_root = root.into();
        }
        if let Some(config) = var("XRAY_CONFIG") {
            layout.xray_config = config.into();
        }
        if let Some(secs) = var("CONFIRM_SECS").and_then(|secs| secs.to_str()?.parse().ok()) {
            layout.confirm_window = Duration::from_secs(secs);
        }
        layout
    }

    pub fn jobs(&self) -> PathBuf {
        self.state.join("jobs")
    }

    pub fn requests(&self) -> PathBuf {
        self.state.join("requests")
    }

    pub fn confirms(&self) -> PathBuf {
        self.state.join("confirm")
    }

    pub fn staged_config(&self) -> PathBuf {
        self.state.join("staged").join("config.json")
    }

    pub fn snapshots(&self) -> PathBuf {
        self.state.join("snapshots")
    }

    pub fn current(&self) -> PathBuf {
        self.xray_root.join("current")
    }

    pub fn xray_binary(&self, version: &Version) -> PathBuf {
        self.xray_root.join(version.as_str()).join("xray")
    }
}
