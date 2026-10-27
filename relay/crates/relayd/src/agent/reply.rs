use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

use crate::{Error, Kind};

/// A refusal with the status that tells the client what to do about it.
#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    pub fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
        }
    }

    pub fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, "unauthorized")
    }

    pub fn forbidden() -> Self {
        Self::new(StatusCode::FORBIDDEN, "this key's role may not do that")
    }
}

impl From<Error> for ApiError {
    fn from(error: Error) -> Self {
        let status = match error.kind() {
            Kind::Invalid => StatusCode::BAD_REQUEST,
            Kind::Missing => StatusCode::NOT_FOUND,
            Kind::Conflict => StatusCode::CONFLICT,
            Kind::Unsupported => StatusCode::NOT_IMPLEMENTED,
            Kind::Failed => StatusCode::INTERNAL_SERVER_ERROR,
        };
        Self::new(status, error.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "error": self.message }))).into_response()
    }
}
