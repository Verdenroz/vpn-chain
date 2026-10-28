use std::fs;
use std::io::Write;
use std::net::IpAddr;
use std::path::Path;
use std::process::{Command, Stdio};

use crate::Error;

/// The nftables table relayd owns. Nothing else in the host's ruleset is read or changed.
pub const TABLE: &str = "vpn_chain_egress";

/// The user xray runs as, whose connections the rules limit.
pub const USER: &str = "xray";

/// Where the resolvers xray uses are listed.
pub const RESOLV_CONF: &str = "/etc/resolv.conf";

const PRIVATE_V4: &str = "0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16, \
     172.16.0.0/12, 192.168.0.0/16, 198.18.0.0/15";
const PRIVATE_V6: &str = "::1, fc00::/7, fe80::/10";

/// The addresses `/etc/resolv.conf` names as nameservers.
pub fn resolvers(resolv_conf: &str) -> Vec<IpAddr> {
    resolv_conf
        .lines()
        .filter_map(|line| line.strip_prefix("nameserver"))
        .filter_map(|address| address.trim().parse().ok())
        .collect()
}

/// [`ruleset`] for the resolvers `resolv_conf` names.
pub fn rules(agent_port: u16, resolv_conf: &Path) -> Result<String, Error> {
    let conf = fs::read_to_string(resolv_conf).map_err(|error| Error::at(resolv_conf, error))?;
    Ok(ruleset(agent_port, &resolvers(&conf)))
}

/// An nft script that replaces the table with rules keeping xray's own connections off this
/// machine's loopback and private networks, except the agent and the resolvers.
///
/// xray dials whatever a client names, so a name that resolves to a private address after the
/// routing rules ran would otherwise reach services that trust local callers. Every resolver
/// takes DNS only, matched on the destination port the caller asked for, since a stub such as
/// Docker's sits behind a port redirect, and no other port of a loopback stub is open. Replies
/// on a connection someone opened to xray, such as relayd's to its API, pass: only connections
/// xray starts are limited.
pub fn ruleset(agent_port: u16, resolvers: &[IpAddr]) -> String {
    let allowed: Vec<String> = resolvers.iter().map(|address| allow(*address)).collect();
    format!(
        "table inet {TABLE}\n\
         delete table inet {TABLE}\n\
         table inet {TABLE} {{\n\
         \tchain output {{\n\
         \t\ttype filter hook output priority filter; policy accept;\n\
         \t\tmeta skuid != {USER} return\n\
         \t\tct state established,related accept\n\
         \t\tip daddr 127.0.0.1 tcp dport {agent_port} accept\n\
         {allowed}\
         \t\tip daddr {{ {PRIVATE_V4} }} reject\n\
         \t\tip6 daddr {{ {PRIVATE_V6} }} reject\n\
         \t}}\n\
         }}\n",
        allowed = allowed.concat()
    )
}

fn allow(address: IpAddr) -> String {
    let family = if address.is_ipv4() { "ip" } else { "ip6" };
    let port = if address.is_loopback() {
        "ct original proto-dst"
    } else {
        "th dport"
    };
    format!("\t\t{family} daddr {address} meta l4proto {{ tcp, udp }} {port} 53 accept\n")
}

/// Loads `ruleset` into the kernel, replacing the table it names.
pub fn apply(ruleset: &str) -> Result<(), Error> {
    let mut nft = Command::new("nft")
        .args(["-f", "-"])
        .stdin(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| Error::new(format!("nft: {error}")))?;
    nft.stdin
        .take()
        .expect("stdin was piped")
        .write_all(ruleset.as_bytes())
        .map_err(|error| Error::new(format!("nft: {error}")))?;
    let output = nft
        .wait_with_output()
        .map_err(|error| Error::new(format!("nft: {error}")))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(Error::new(format!(
            "nft rejected the egress rules: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )))
    }
}
