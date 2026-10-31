use std::net::IpAddr;

use relayd::egress::{TABLE, resolvers, ruleset};

fn address(text: &str) -> IpAddr {
    text.parse().unwrap()
}

#[test]
fn resolvers_are_the_nameserver_lines_and_nothing_else() {
    let conf = "# generated\nsearch lan\nnameserver 127.0.0.53\nnameserver 10.0.0.2 \noptions edns0\nnameserver fe80::1%eth0\nnameserver 2606:4700:4700::1111\n";

    assert_eq!(
        resolvers(conf),
        [
            address("127.0.0.53"),
            address("10.0.0.2"),
            address("2606:4700:4700::1111")
        ]
    );
}

#[test]
fn the_rules_touch_only_their_own_table_and_only_xrays_connections() {
    let rules = ruleset(10086, &[]);

    assert!(rules.starts_with(&format!("table inet {TABLE}\ndelete table inet {TABLE}\n")));
    assert_eq!(rules.matches("table inet").count(), 3);
    assert!(rules.contains("meta skuid != xray return"));
}

#[test]
fn xray_may_reach_the_agent_and_no_other_loopback_port() {
    let rules = ruleset(10086, &[]);

    assert!(rules.contains("ip daddr 127.0.0.1 tcp dport 10086 accept"));
    assert!(rules.contains("127.0.0.0/8"));
    assert!(rules.contains("ip6 daddr { ::1,"));
    assert!(rules.find("accept").unwrap() < rules.find("reject").unwrap());
}

#[test]
fn replies_to_a_local_caller_pass_but_a_connection_xray_starts_does_not() {
    let rules = ruleset(10086, &[]);

    let replies = rules.find("ct state established,related accept").unwrap();
    assert!(replies < rules.find("reject").unwrap());
    assert!(!rules.contains("ct state new"));
}

#[test]
fn private_and_link_local_ranges_are_rejected() {
    let rules = ruleset(10086, &[]);

    for range in [
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "198.18.0.0/15",
        "fc00::/7",
        "fe80::/10",
    ] {
        assert!(rules.contains(range), "{range}");
    }
}

#[test]
fn a_forwarded_port_passes_only_when_xray_dialled_a_public_address() {
    let rules = ruleset(10086, &[]);

    let v4 = rules
        .lines()
        .find(|line| line.contains("ct original ip daddr"))
        .unwrap();
    let v6 = rules
        .lines()
        .find(|line| line.contains("ct original ip6 daddr"))
        .unwrap();
    assert!(
        v4.trim_start()
            .starts_with("ct status dnat ct original ip daddr != {")
    );
    assert!(
        v6.trim_start()
            .starts_with("ct status dnat ct original ip6 daddr != {")
    );
    for range in [
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
    ] {
        assert!(v4.contains(range), "{range}");
    }
    for range in ["::1", "fc00::/7", "fe80::/10"] {
        assert!(v6.contains(range), "{range}");
    }
    assert!(rules.find("ct status dnat").unwrap() < rules.find("reject").unwrap());
}

#[test]
fn every_resolver_takes_dns_and_nothing_else() {
    let rules = ruleset(
        10086,
        &[
            address("127.0.0.53"),
            address("10.0.0.2"),
            address("fd00::2"),
        ],
    );

    assert!(
        rules.contains(
            "ip daddr 127.0.0.53 meta l4proto { tcp, udp } ct original proto-dst 53 accept"
        )
    );
    assert!(!rules.contains("ip daddr 127.0.0.53 accept"));
    assert!(rules.contains("ip daddr 10.0.0.2 meta l4proto { tcp, udp } th dport 53 accept"));
    assert!(rules.contains("ip6 daddr fd00::2 meta l4proto { tcp, udp } th dport 53 accept"));
    assert!(rules.find("th dport 53").unwrap() < rules.find("reject").unwrap());
}
