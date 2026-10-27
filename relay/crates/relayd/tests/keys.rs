mod common;

use common::{key_for, public_der, relay, signer};
use p256::pkcs8::EncodePublicKey;
use relayd::{Key, Kind, Role};

#[test]
fn a_key_reads_the_same_from_pem_and_der() {
    let signer = signer(7);
    let pem = signer
        .verifying_key()
        .to_public_key_pem(Default::default())
        .unwrap();

    let from_pem = Key::new("phone", Role::Admin, pem.as_bytes(), "now".to_owned()).unwrap();
    let from_der = Key::new("phone", Role::Admin, &public_der(&signer), "now".to_owned()).unwrap();

    assert_eq!(from_pem, from_der);
    assert_eq!(from_pem.id.len(), 16);
}

#[test]
fn different_keys_have_different_ids() {
    let one = key_for("phone", Role::Member, &signer(7));
    let two = key_for("phone", Role::Member, &signer(8));

    assert_ne!(one.id, two.id);
}

#[test]
fn something_that_is_not_a_p256_public_key_is_refused() {
    for bytes in [
        &b"not a key"[..],
        b"-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----",
    ] {
        let error = Key::new("phone", Role::Member, bytes, "now".to_owned()).unwrap_err();

        assert_eq!(error.kind(), Kind::Invalid);
    }
}

#[test]
fn a_relay_with_keys_validates() {
    let mut relay = relay("relay-direct");
    relay.state.keys = vec![
        key_for("laptop", Role::Admin, &signer(7)),
        key_for("shared", Role::Member, &signer(8)),
    ];

    relay.validate().unwrap();
}

#[test]
fn a_key_for_a_device_that_does_not_exist_is_refused() {
    let mut relay = relay("relay-direct");
    relay.state.keys = vec![key_for("ghost", Role::Member, &signer(7))];

    let error = relay.validate().unwrap_err().to_string();

    assert!(error.contains("a device that does not exist"), "{error}");
}

#[test]
fn a_key_listed_twice_is_refused() {
    let mut relay = relay("relay-direct");
    let key = key_for("laptop", Role::Admin, &signer(7));
    relay.state.keys = vec![key.clone(), key];

    let error = relay.validate().unwrap_err().to_string();

    assert!(error.contains("is listed twice"), "{error}");
}

#[test]
fn a_key_whose_id_does_not_match_its_public_key_is_refused() {
    let mut relay = relay("relay-direct");
    let mut key = key_for("laptop", Role::Admin, &signer(7));
    key.id = "0000000000000000".to_owned();
    relay.state.keys = vec![key];

    let error = relay.validate().unwrap_err().to_string();

    assert!(
        error.contains("not a P-256 public key matching its id"),
        "{error}"
    );
}
