mod common;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use common::{key_for, signer};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use relayd::agent::{Principal, Signed, Verifier, WINDOW, Why, message};
use relayd::{Key, Role};

const NOW: u64 = 1_793_000_000;
const NONCE: &str = "0123456789abcdef";

struct Request {
    method: &'static str,
    target: &'static str,
    body: &'static [u8],
    key: String,
    timestamp: String,
    nonce: String,
    signature: String,
}

impl Request {
    fn signed_by(signer: &SigningKey, key: &Key) -> Self {
        let mut request = Self {
            method: "POST",
            target: "/v1/devices",
            body: br#"{"name":"phone"}"#,
            key: key.id.clone(),
            timestamp: NOW.to_string(),
            nonce: NONCE.to_owned(),
            signature: String::new(),
        };
        request.sign(signer);
        request
    }

    fn sign(&mut self, signer: &SigningKey) {
        let message = message(
            self.method,
            self.target,
            self.body,
            &self.timestamp,
            &self.nonce,
        );
        let signature: Signature = signer.sign(message.as_bytes());
        self.signature = STANDARD.encode(signature.to_der().as_bytes());
    }

    fn view(&self) -> Signed<'_> {
        Signed {
            method: self.method,
            target: self.target,
            body: self.body,
            key: Some(&self.key),
            timestamp: Some(&self.timestamp),
            nonce: Some(&self.nonce),
            signature: Some(&self.signature),
        }
    }
}

fn keys() -> Vec<Key> {
    vec![
        key_for("laptop", Role::Admin, &signer(7)),
        key_for("phone", Role::Member, &signer(8)),
    ]
}

fn why(verifier: &Verifier, request: &Request, now: u64) -> Why {
    verifier
        .verify(&keys(), &request.view(), now)
        .unwrap_err()
        .why
}

#[test]
fn the_signed_message_is_the_five_fields_after_the_context() {
    let text = message("POST", "/v1/devices", b"{}", "100", NONCE);

    assert_eq!(
        text,
        "vpn-chain-relay-v1\nPOST\n/v1/devices\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n100\n0123456789abcdef"
    );
}

#[test]
fn a_good_signature_names_the_key_its_device_and_its_role() {
    let keys = keys();
    let request = Request::signed_by(&signer(7), &keys[0]);

    let who = Verifier::default()
        .verify(&keys, &request.view(), NOW)
        .unwrap();

    assert_eq!(
        who,
        Principal {
            key: keys[0].id.clone(),
            device: "laptop".to_owned(),
            role: Role::Admin,
        }
    );
}

#[test]
fn a_request_missing_any_signing_header_is_unsigned() {
    let keys = keys();
    let request = Request::signed_by(&signer(7), &keys[0]);
    let whole = request.view();

    let without = [
        Signed {
            key: None,
            ..request.view()
        },
        Signed {
            timestamp: None,
            ..request.view()
        },
        Signed {
            nonce: None,
            ..request.view()
        },
        Signed {
            signature: None,
            ..whole
        },
    ];

    for partial in without {
        let rejected = Verifier::default()
            .verify(&keys, &partial, NOW)
            .unwrap_err();
        assert_eq!(rejected.why, Why::Unsigned);
    }
}

#[test]
fn a_key_relayd_does_not_hold_is_refused_without_naming_it() {
    let stranger = key_for("laptop", Role::Admin, &signer(9));
    let request = Request::signed_by(&signer(9), &stranger);

    let rejected = Verifier::default()
        .verify(&keys(), &request.view(), NOW)
        .unwrap_err();

    assert_eq!((rejected.why, rejected.key), (Why::UnknownKey, None));
}

#[test]
fn a_signature_from_another_key_is_refused_and_names_the_key_claimed() {
    let keys = keys();
    let request = Request::signed_by(&signer(8), &keys[0]);

    let rejected = Verifier::default()
        .verify(&keys, &request.view(), NOW)
        .unwrap_err();

    assert_eq!(rejected.why, Why::BadSignature);
    assert_eq!(rejected.key, Some(keys[0].id.clone()));
}

#[test]
fn a_tampered_method_target_body_timestamp_or_nonce_is_refused() {
    let keys = keys();
    let edits: [fn(&mut Request); 5] = [
        |request| request.method = "DELETE",
        |request| request.target = "/v1/devices/laptop",
        |request| request.body = br#"{"name":"tablet"}"#,
        |request| request.timestamp = (NOW + 1).to_string(),
        |request| request.nonce = "fedcba9876543210".to_owned(),
    ];

    for edit in edits {
        let mut request = Request::signed_by(&signer(7), &keys[0]);
        edit(&mut request);

        assert_eq!(why(&Verifier::default(), &request, NOW), Why::BadSignature);
    }
}

#[test]
fn a_garbled_signature_is_refused() {
    let keys = keys();
    for garbage in ["", "not base64!", "AAAA"] {
        let mut request = Request::signed_by(&signer(7), &keys[0]);
        request.signature = garbage.to_owned();

        assert_eq!(why(&Verifier::default(), &request, NOW), Why::BadSignature);
    }
}

#[test]
fn a_signature_with_a_high_s_value_verifies() {
    let keys = keys();
    let mut request = Request::signed_by(&signer(7), &keys[0]);
    let der = STANDARD.decode(&request.signature).unwrap();
    let signature = Signature::from_der(&der).unwrap();
    let (r, s) = signature.split_scalars();
    let flipped = Signature::from_scalars(r, -*s).unwrap();
    let high = if signature.normalize_s() != signature {
        signature
    } else {
        flipped
    };
    assert_ne!(high.normalize_s(), high);
    request.signature = STANDARD.encode(high.to_der().as_bytes());

    assert!(
        Verifier::default()
            .verify(&keys, &request.view(), NOW)
            .is_ok()
    );
}

#[test]
fn a_nonce_that_is_too_short_is_refused() {
    let keys = keys();
    let mut request = Request::signed_by(&signer(7), &keys[0]);
    request.nonce = "short".to_owned();
    request.sign(&signer(7));

    assert_eq!(why(&Verifier::default(), &request, NOW), Why::BadSignature);
}

#[test]
fn the_time_window_is_two_minutes_either_side() {
    let keys = keys();
    let request = Request::signed_by(&signer(7), &keys[0]);

    for now in [NOW - WINDOW, NOW + WINDOW] {
        assert!(
            Verifier::default()
                .verify(&keys, &request.view(), now)
                .is_ok(),
            "{now}"
        );
    }
    for now in [NOW - WINDOW - 1, NOW + WINDOW + 1] {
        assert_eq!(
            why(&Verifier::default(), &request, now),
            Why::Stale,
            "{now}"
        );
    }
}

#[test]
fn a_timestamp_that_is_not_a_number_is_stale() {
    let keys = keys();
    let mut request = Request::signed_by(&signer(7), &keys[0]);
    request.timestamp = "yesterday".to_owned();
    request.sign(&signer(7));

    assert_eq!(why(&Verifier::default(), &request, NOW), Why::Stale);
}

#[test]
fn the_same_request_twice_is_a_replay() {
    let keys = keys();
    let verifier = Verifier::default();
    let request = Request::signed_by(&signer(7), &keys[0]);

    verifier.verify(&keys, &request.view(), NOW).unwrap();

    assert_eq!(why(&verifier, &request, NOW), Why::Replayed);
    assert_eq!(why(&verifier, &request, NOW + 5), Why::Replayed);
}

#[test]
fn a_replay_is_caught_for_as_long_as_its_timestamp_could_pass() {
    let keys = keys();
    let verifier = Verifier::default();
    let mut request = Request::signed_by(&signer(7), &keys[0]);
    request.timestamp = (NOW + WINDOW).to_string();
    request.sign(&signer(7));

    verifier.verify(&keys, &request.view(), NOW).unwrap();

    assert_eq!(why(&verifier, &request, NOW + 2 * WINDOW), Why::Replayed);
}

#[test]
fn a_refused_request_does_not_use_up_its_nonce() {
    let keys = keys();
    let verifier = Verifier::default();
    let mut request = Request::signed_by(&signer(7), &keys[0]);
    request.body = b"tampered";
    assert_eq!(why(&verifier, &request, NOW), Why::BadSignature);
    request.sign(&signer(7));

    assert!(verifier.verify(&keys, &request.view(), NOW).is_ok());
}

#[test]
fn two_keys_may_use_the_same_nonce() {
    let keys = keys();
    let verifier = Verifier::default();

    for (seed, key) in [(7, &keys[0]), (8, &keys[1])] {
        let request = Request::signed_by(&signer(seed), key);
        assert!(verifier.verify(&keys, &request.view(), NOW).is_ok());
    }
}

#[test]
fn the_nonce_cache_refuses_new_requests_when_full_and_frees_room_as_nonces_expire() {
    let keys = keys();
    let verifier = Verifier::with_capacity(2);
    let nonces = ["aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "cccccccccccccccc"];
    let signed = |nonce: &str| {
        let mut request = Request::signed_by(&signer(7), &keys[0]);
        request.nonce = nonce.to_owned();
        request.sign(&signer(7));
        request
    };
    for nonce in &nonces[..2] {
        verifier.verify(&keys, &signed(nonce).view(), NOW).unwrap();
    }

    assert_eq!(why(&verifier, &signed(nonces[2]), NOW), Why::Overloaded);

    let mut later = signed(nonces[2]);
    later.timestamp = (NOW + 3 * WINDOW).to_string();
    later.sign(&signer(7));
    assert!(
        verifier
            .verify(&keys, &later.view(), NOW + 3 * WINDOW)
            .is_ok()
    );
}

#[test]
fn one_key_filling_its_quota_does_not_lock_another_out() {
    let keys = keys();
    let verifier = Verifier::with_capacity(1);
    let admin = Request::signed_by(&signer(7), &keys[0]);
    verifier.verify(&keys, &admin.view(), NOW).unwrap();
    let mut second = Request::signed_by(&signer(7), &keys[0]);
    second.nonce = "bbbbbbbbbbbbbbbb".to_owned();
    second.sign(&signer(7));
    assert_eq!(why(&verifier, &second, NOW), Why::Overloaded);

    let member = Request::signed_by(&signer(8), &keys[1]);

    assert!(verifier.verify(&keys, &member.view(), NOW).is_ok());
}
