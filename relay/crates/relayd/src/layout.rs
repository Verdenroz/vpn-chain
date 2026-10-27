//! Where a relay installed by `relayd install` keeps its files.

/// The job runner's state, which `relay-privileged` also reads.
pub const JOBS_DIR: &str = "/var/lib/vpn-chain";

/// relayd's own files, which only the `relayd` user and root can open.
pub const RELAY_DIR: &str = "/var/lib/vpn-chain/relay";

pub const STATE: &str = "/var/lib/vpn-chain/relay/relay.json";

pub const XRAY_CONFIG: &str = "/usr/local/etc/xray/config.json";

pub const XRAY_ROOT: &str = "/opt/vpn-chain/xray";

pub const XRAY: &str = "/opt/vpn-chain/xray/current/xray";
