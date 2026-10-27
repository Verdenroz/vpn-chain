use std::time::Duration;

use tonic::{Code, Status};

/// Why an xray API call failed. No variant carries a user's UUID.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("xray API did not answer within {0:?}")]
    Timeout(Duration),
    #[error("xray API is unreachable: {0}")]
    Unavailable(String),
    #[error("user {0} is already on the inbound")]
    UserExists(String),
    #[error("user {0} is not on the inbound")]
    UserNotFound(String),
    #[error("user {0} has no readable VLESS account")]
    Account(String),
    #[error("xray API call failed ({code:?}): {message}")]
    Rpc { code: Code, message: String },
}

impl From<Status> for Error {
    fn from(status: Status) -> Self {
        match status.code() {
            Code::Unavailable => Self::Unavailable(status.message().to_owned()),
            code => Self::Rpc {
                code,
                message: status.message().to_owned(),
            },
        }
    }
}
