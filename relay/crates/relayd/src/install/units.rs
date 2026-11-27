use std::path::Path;

use super::host::{put, run, systemctl};
use crate::Error;

/// The packaging files, the update script and the BBR sysctl, as `(destination, mode, contents)`.
const FILES: [(&str, u32, &str); 12] = [
    (
        "/etc/sysusers.d/vpn-chain.conf",
        0o644,
        include_str!("../../../../packaging/systemd/vpn-chain.sysusers"),
    ),
    (
        "/etc/tmpfiles.d/vpn-chain.conf",
        0o644,
        include_str!("../../../../packaging/systemd/vpn-chain.tmpfiles"),
    ),
    (
        "/etc/systemd/system/xray.service",
        0o644,
        include_str!("../../../../packaging/systemd/xray.service"),
    ),
    (
        "/etc/systemd/system/xray.service.d/10-vpn-chain.conf",
        0o644,
        include_str!("../../../../packaging/systemd/xray.service.d/10-vpn-chain.conf"),
    ),
    (
        "/etc/systemd/system/relay-privileged.path",
        0o644,
        include_str!("../../../../packaging/systemd/relay-privileged.path"),
    ),
    (
        "/etc/systemd/system/relay-privileged.service",
        0o644,
        include_str!("../../../../packaging/systemd/relay-privileged.service"),
    ),
    (
        "/etc/systemd/system/relayd.service",
        0o644,
        include_str!("../../../../packaging/systemd/relayd.service"),
    ),
    (
        "/etc/systemd/system/vpn-chain-egress.service",
        0o644,
        include_str!("../../../../packaging/systemd/vpn-chain-egress.service"),
    ),
    (
        "/etc/systemd/system/vpn-chain-update.service",
        0o644,
        include_str!("../../../../packaging/systemd/vpn-chain-update.service"),
    ),
    (
        "/usr/local/libexec/vpn-chain/relay-update",
        0o755,
        include_str!("../../../../packaging/relay-update.sh"),
    ),
    (
        "/etc/systemd/system/vpn-chain-update.timer",
        0o644,
        include_str!("../../../../packaging/systemd/vpn-chain-update.timer"),
    ),
    (
        "/etc/sysctl.d/99-vpn-chain-bbr.conf",
        0o644,
        "net.core.default_qdisc=fq\nnet.ipv4.tcp_congestion_control=bbr\nnet.core.wmem_max=33554432\nnet.ipv4.tcp_wmem=4096 65536 33554432\n",
    ),
];

/// Returns whether any file changed.
pub fn install() -> Result<bool, Error> {
    let mut changed = false;
    for (path, mode, contents) in FILES {
        changed |= put(Path::new(path), contents.as_bytes(), mode)?;
    }
    Ok(changed)
}

pub fn accounts() -> Result<(), Error> {
    run("systemd-sysusers", &[])?;
    run("systemd-tmpfiles", &["--create"])
}

pub fn reload() -> Result<(), Error> {
    systemctl(&["daemon-reload"])
}
