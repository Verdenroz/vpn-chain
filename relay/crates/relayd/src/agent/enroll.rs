use std::path::Path;
use std::sync::{Arc, Mutex};

use axum::Json;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::Deserialize;
use serde_json::json;
use tokio::task::spawn_blocking;

use super::reply::ApiError;
use super::{LOCK_WAIT, Service};
use crate::clock::seconds;
use crate::enroll::{Enrollments, Verdict};
use crate::{Error, Key, add_key};

const FAILURES_PER_MINUTE: usize = 5;
const MINUTE: u64 = 60;

/// Claims a pairing token for a device's management key, and counts the failures.
pub struct Enroll {
    tokens: Enrollments,
    failures: Mutex<Vec<u64>>,
}

impl Enroll {
    pub fn beside(state: &Path) -> Self {
        Self {
            tokens: Enrollments::beside(state),
            failures: Mutex::default(),
        }
    }

    fn blocked(&self, now: u64) -> bool {
        let mut failures = self.failures.lock().expect("no holder of the count panics");
        failures.retain(|at| at + MINUTE > now);
        failures.len() >= FAILURES_PER_MINUTE
    }

    fn failed(&self, now: u64) {
        self.failures
            .lock()
            .expect("no holder of the count panics")
            .push(now);
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    token: String,
    public_key: String,
}

/// `POST /v1/enroll`, the one route a request needs no signature for: the token is the proof.
/// A good token registers the key, is burned, and rekeys its device, so a photographed link
/// is useless afterwards.
pub async fn enroll(State(service): State<Arc<Service>>, body: Bytes) -> Response {
    let done = spawn_blocking(move || claim(&service, &body)).await;
    match done {
        Ok(Ok(reply)) => Json(reply).into_response(),
        Ok(Err(refusal)) => refusal.into_response(),
        Err(_) => {
            ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "internal error").into_response()
        }
    }
}

fn claim(service: &Service, body: &[u8]) -> Result<serde_json::Value, ApiError> {
    let now = seconds();
    if service.enroll.blocked(now) {
        return Err(ApiError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "too many failed attempts; try again shortly",
        ));
    }
    let refuse = |status: ApiError, result: &str| {
        service.enroll.failed(now);
        service.audit.record("-", "enroll", None, result);
        status
    };
    let request: Request = serde_json::from_slice(body).map_err(|_| {
        refuse(
            ApiError::from(Error::invalid("malformed request body")),
            "malformed",
        )
    })?;
    let public_key = STANDARD
        .decode(&request.public_key)
        .map_err(|_| Error::invalid("not a base64 public key"))
        .and_then(|der| Key::check_public(&der).map(|()| der))
        .map_err(|error| refuse(error.into(), "bad key"))?;
    let verdict = service
        .enroll
        .tokens
        .claim(&service.lock, LOCK_WAIT, &request.token, now)?;
    let (device, role) = match verdict {
        Verdict::Claimed { device, role } => (device, role),
        Verdict::Unknown => return Err(refuse(ApiError::unauthorized(), "unknown token")),
        Verdict::Expired => {
            return Err(refuse(
                ApiError::new(StatusCode::GONE, "this pairing link has expired"),
                "expired token",
            ));
        }
    };
    let key = add_key(
        &service.state,
        &service.secrets,
        &service.lock,
        LOCK_WAIT,
        &device,
        role,
        &public_key,
    )
    .inspect_err(|_| service.audit.record("-", "enroll", Some(&device), "failed"))?;
    let (identity, _) = service
        .operate(|manager| manager.reissue(&device, None))
        .inspect_err(|_| {
            service
                .audit
                .record(&key.id, "enroll", Some(&device), "failed")
        })?;
    service.audit.record(&key.id, "enroll", Some(&device), "ok");
    Ok(json!({ "identity": identity, "keyId": key.id, "role": role }))
}
