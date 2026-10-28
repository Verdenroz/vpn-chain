//! What the xray API does to a running relay, measured against Xray v26.3.27
//! in docker with the sing-box clients the apps ship. Needs docker with host
//! networking: `cargo test --test live -- --ignored`.

mod relay;

use live_harness::{Tunnel, echo_server, roundtrip, roundtrip_until};
use xray_api::{Error, Traffic};

use relay::{Device, INBOUND, Relay, SING_BOX};

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs docker"]
async fn added_user_with_a_pooled_short_id_connects_while_xray_keeps_running() {
    let relay = Relay::start("add").await;
    let target = echo_server().await;
    let before = relay.xray_process().await;

    for (version, short_id) in SING_BOX.into_iter().zip(&relay.pool) {
        let device = Device::new(&format!("phone-{version}"), short_id);
        let proxy = relay.client(&device, version).await;
        assert!(
            roundtrip(proxy, target, b"ping").await.is_err(),
            "sing-box {version}"
        );

        relay
            .api
            .add_user(INBOUND, device.user.clone())
            .await
            .unwrap();
        roundtrip_until(proxy, "127.0.0.1", target, b"ping")
            .await
            .unwrap_or_else(|e| panic!("sing-box {version}: {e}"));
    }
    assert_eq!(relay.xray_process().await, before);
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs docker"]
async fn removed_user_is_refused_on_new_connections() {
    let relay = Relay::start("remove").await;
    let target = echo_server().await;

    for version in SING_BOX {
        let device = Device::new(&format!("phone-{version}"), &relay.pool[0]);
        relay
            .api
            .add_user(INBOUND, device.user.clone())
            .await
            .unwrap();
        let proxy = relay.client(&device, version).await;
        roundtrip_until(proxy, "127.0.0.1", target, b"ping")
            .await
            .unwrap();

        relay
            .api
            .remove_user(INBOUND, &device.user.email)
            .await
            .unwrap();
        assert!(
            roundtrip(proxy, target, b"ping").await.is_err(),
            "sing-box {version}"
        );
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs docker"]
async fn removed_users_established_connection_keeps_working() {
    let relay = Relay::start("established").await;
    let target = echo_server().await;

    for version in SING_BOX {
        let device = Device::new(&format!("phone-{version}"), &relay.pool[0]);
        relay
            .api
            .add_user(INBOUND, device.user.clone())
            .await
            .unwrap();
        let proxy = relay.client(&device, version).await;
        let mut open = Tunnel::open(proxy, target).await.unwrap();
        open.echo(b"before").await.unwrap();

        relay
            .api
            .remove_user(INBOUND, &device.user.email)
            .await
            .unwrap();
        assert!(
            roundtrip(proxy, target, b"ping").await.is_err(),
            "sing-box {version}"
        );
        open.echo(b"after")
            .await
            .unwrap_or_else(|e| panic!("sing-box {version}: {e}"));
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs docker"]
async fn user_traffic_counts_an_added_users_bytes_and_reset_zeroes_them() {
    let relay = Relay::start("stats").await;
    let target = echo_server().await;
    let payload = vec![7; 64 * 1024];

    for version in SING_BOX {
        let device = Device::new(&format!("phone-{version}"), &relay.pool[0]);
        let email = Some(device.user.email.as_str());
        relay
            .api
            .add_user(INBOUND, device.user.clone())
            .await
            .unwrap();
        let proxy = relay.client(&device, version).await;
        roundtrip_until(proxy, "127.0.0.1", target, &payload)
            .await
            .unwrap();

        let counted = relay.api.user_traffic(email, false).await.unwrap();
        let read = relay.api.user_traffic(email, true).await.unwrap();
        let after_reset = relay.api.user_traffic(email, false).await.unwrap();

        let traffic = counted[&device.user.email];
        assert!(
            traffic.up >= payload.len() as u64,
            "sing-box {version}: {traffic:?}"
        );
        assert!(
            traffic.down >= payload.len() as u64,
            "sing-box {version}: {traffic:?}"
        );
        assert_eq!(counted.keys().collect::<Vec<_>>(), [&device.user.email]);
        assert_eq!(read[&device.user.email], traffic);
        assert_eq!(after_reset[&device.user.email], Traffic::default());
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs docker"]
async fn api_added_user_is_gone_after_xray_restarts() {
    let relay = Relay::start("restart").await;
    let target = echo_server().await;
    let device = Device::new("phone", &relay.pool[0]);
    relay
        .api
        .add_user(INBOUND, device.user.clone())
        .await
        .unwrap();
    let mut proxies = Vec::new();
    for version in SING_BOX {
        let added = relay.client(&device, version).await;
        let seed = relay.client(&relay.seed, version).await;
        roundtrip_until(added, "127.0.0.1", target, b"ping")
            .await
            .unwrap();
        proxies.push((version, added, seed));
    }

    relay.restart_xray().await;

    let users = relay.api.users(INBOUND).await.unwrap();
    assert_eq!(users, std::slice::from_ref(&relay.seed.user));
    for (version, added, seed) in proxies {
        assert!(
            roundtrip(added, target, b"ping").await.is_err(),
            "sing-box {version}"
        );
        roundtrip_until(seed, "127.0.0.1", target, b"ping")
            .await
            .unwrap_or_else(|e| panic!("sing-box {version}: {e}"));
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs docker"]
async fn xray_reports_duplicate_and_missing_users_as_typed_errors() {
    let relay = Relay::start("errors").await;
    let shouted = Device::new("SEED", &relay.pool[0]).user;

    let duplicate = relay.api.add_user(INBOUND, relay.seed.user.clone()).await;
    let case_only = relay.api.add_user(INBOUND, shouted).await;
    let missing = relay.api.remove_user(INBOUND, "nobody").await;
    let no_inbound = relay.api.add_user("nope", Device::new("x", "").user).await;

    assert!(
        matches!(&duplicate, Err(Error::UserExists(e)) if e == "seed"),
        "{duplicate:?}"
    );
    assert!(
        matches!(&case_only, Err(Error::UserExists(e)) if e == "SEED"),
        "{case_only:?}"
    );
    assert!(
        matches!(&missing, Err(Error::UserNotFound(e)) if e == "nobody"),
        "{missing:?}"
    );
    assert!(
        matches!(&no_inbound, Err(Error::Rpc { .. })),
        "{no_inbound:?}"
    );
}
