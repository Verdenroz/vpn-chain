//! Client for the gRPC API Xray-core serves on a relay's loopback port.
//!
//! `HandlerService` adds and removes VLESS users on a running inbound, and
//! `StatsService` reports per-user traffic. Neither restarts xray, and neither
//! persists anything: xray forgets API-added users when it restarts, so the
//! caller must also write each change to `config.json`.
//!
//! The protos under `proto/` are vendored unmodified from Xray-core v26.3.27
//! (MPL-2.0, `proto/LICENSE`).

mod error;
#[cfg(test)]
mod fake;
mod handler;
mod proto;
mod stats;
mod user;

use std::future::Future;
use std::net::SocketAddr;
use std::time::Duration;

use tonic::transport::{Channel, Endpoint};
use tonic::{Response, Status};

pub use error::Error;
pub use stats::{ALL_USERS, Traffic};
pub use user::{UserId, VlessUser};

use proto::xray::app::proxyman::command::handler_service_client::HandlerServiceClient;
use proto::xray::app::stats::command::stats_service_client::StatsServiceClient;

/// A connection to one xray instance's API inbound.
#[derive(Debug, Clone)]
pub struct XrayApi {
    handler: HandlerServiceClient<Channel>,
    stats: StatsServiceClient<Channel>,
    timeout: Duration,
}

impl XrayApi {
    /// Points at the API inbound at `addr` without connecting. No call,
    /// connecting included, waits longer than `timeout`.
    ///
    /// Must be called inside a tokio runtime.
    pub fn new(addr: SocketAddr, timeout: Duration) -> Self {
        let channel = Endpoint::from_shared(format!("http://{addr}"))
            .expect("a socket address is a valid URI")
            .connect_timeout(timeout)
            .connect_lazy();
        Self {
            handler: HandlerServiceClient::new(channel.clone()),
            stats: StatsServiceClient::new(channel),
            timeout,
        }
    }

    async fn within<T>(
        &self,
        call: impl Future<Output = Result<Response<T>, Status>>,
    ) -> Result<T, Error> {
        match tokio::time::timeout(self.timeout, call).await {
            Ok(result) => Ok(result?.into_inner()),
            Err(_) => Err(Error::Timeout(self.timeout)),
        }
    }
}
