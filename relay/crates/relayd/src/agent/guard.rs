use std::sync::Arc;

use axum::body::{Body, to_bytes};
use axum::extract::{Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use tokio::task::spawn_blocking;

use super::reply::ApiError;
use super::{Principal, Service, Signed, Why};
use crate::clock::seconds;

pub const BODY_LIMIT: usize = 16 * 1024;

/// Lets a request through only when a management key relayd holds signed it, and hands the
/// handler who that was. Every route and the fallback sit behind it, so an unsigned probe learns
/// nothing about which paths exist.
pub async fn authenticate(
    State(service): State<Arc<Service>>,
    request: Request,
    next: Next,
) -> Response {
    let (parts, body) = request.into_parts();
    let Ok(body) = to_bytes(body, BODY_LIMIT).await else {
        return ApiError::new(StatusCode::PAYLOAD_TOO_LARGE, "request body too large")
            .into_response();
    };
    let method = parts.method.to_string();
    let target = parts
        .uri
        .path_and_query()
        .map_or_else(|| parts.uri.path(), |target| target.as_str())
        .to_owned();
    let headers = parts.headers.clone();
    let verdict = {
        let (service, body) = (service.clone(), body.clone());
        spawn_blocking(move || verify(&service, &method, &target, &body, &headers)).await
    };
    match verdict {
        Ok(Ok(principal)) => {
            let mut request = Request::from_parts(parts, Body::from(body));
            request.extensions_mut().insert(principal);
            next.run(request).await
        }
        Ok(Err(refusal)) => refusal.into_response(),
        Err(_) => {
            ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "internal error").into_response()
        }
    }
}

fn verify(
    service: &Service,
    method: &str,
    target: &str,
    body: &[u8],
    headers: &HeaderMap,
) -> Result<Principal, ApiError> {
    let header = |name: &str| headers.get(name).and_then(|value| value.to_str().ok());
    let signed = Signed {
        method,
        target,
        body,
        key: header("x-key-id"),
        timestamp: header("x-timestamp"),
        nonce: header("x-nonce"),
        signature: header("x-signature"),
    };
    let relay = service.load().map_err(|error| {
        eprintln!("relayd: {error}");
        ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "internal error")
    })?;
    service
        .verifier
        .verify(&relay.state.keys, &signed, seconds())
        .map_err(|refusal| {
            if let Some(key) = &refusal.key {
                service
                    .audit
                    .record(key, "auth", None, &refusal.why.to_string());
            }
            match refusal.why {
                Why::Overloaded => ApiError::new(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "too many recent requests; try again shortly",
                ),
                _ => ApiError::unauthorized(),
            }
        })
}
