//! The agent: the HTTP API the app reaches through its own tunnel to manage the relay.
//!
//! It listens on loopback only. xray routes the reserved name [`crate::AGENT_HOST`] to that port
//! for any client that authenticated to REALITY, so the API has no public address to scan. The
//! name is matched on what the client sends, including an HTTP Host header or TLS server name.
//! Each request must also carry a signature from a management key relayd holds, which is what
//! tells an admin from a member.
//!
//! Routes, all JSON under `/v1`; the signed message is described at [`message`]:
//!
//! | Route | Who |
//! |---|---|
//! | `GET /relay` | any key |
//! | `GET /devices` | admin: all; member: its own device |
//! | `POST /devices` | admin |
//! | `PATCH /devices/{name}`, `POST /pool/refill` | admin |
//! | `DELETE /devices/{name}` | admin, for a device other than its own |
//! | `POST /devices/{name}/rekey` | admin, or the member whose device it is |
//! | `POST /devices/{name}/pair` | admin, for a device other than its own |
//! | `GET /exit`, `PUT /exit` | admin |
//! | `POST /jobs`, `GET /jobs/{id}`, `POST /jobs/{id}/confirm`, `GET /audit` | admin |
//! | `POST /enroll` | a pairing token |
//!
//! `POST /enroll` is the one route that takes no signature: its pairing token authenticates it.
//!
//! `/devices/{name}/claim` and `/invites` are left unrouted for claim-and-invite.

mod audit;
mod auth;
mod enroll;
mod guard;
mod reply;
mod routes;

use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use relay_jobs::{JobId, Jobs};
use tokio::net::TcpListener;
use tokio::runtime::Builder;

use crate::{Error, JobsRestart, Live, Manager, Placement, Restart, now};

pub use audit::{Audit, Entry};
pub use auth::{CONTEXT, Principal, Reject, Signed, Verifier, WINDOW, Why, message};
pub use enroll::Enroll;
pub use routes::router;

const LOCK_WAIT: Duration = Duration::from_secs(10);

/// What the agent serves: one relay's files, its xray, and how it restarts it.
pub struct Service {
    pub state: PathBuf,
    pub secrets: PathBuf,
    pub config: PathBuf,
    pub lock: PathBuf,
    pub xray: PathBuf,
    pub group: String,
    pub spares: usize,
    pub live: Arc<dyn Live + Send + Sync>,
    /// How a restart happens when there is no job runner.
    pub restart: Arc<dyn Restart + Send + Sync>,
    /// The job runner's queue. With it, a restart is a job the client confirms; without it the
    /// job routes answer 501.
    pub jobs: Option<Jobs>,
    pub audit: Audit,
    pub verifier: Verifier,
    pub enroll: Enroll,
}

impl Service {
    fn load(&self) -> Result<crate::Relay, Error> {
        crate::Relay::load(&self.state, &self.secrets)
    }

    fn jobs(&self) -> Result<&Jobs, Error> {
        self.jobs
            .as_ref()
            .ok_or_else(|| Error::unsupported("this relay has no job runner"))
    }

    /// Runs `operate` on a [`Manager`] and returns the job a deferred restart was handed to.
    fn operate<T>(
        &self,
        operate: impl FnOnce(&Manager) -> Result<T, Error>,
    ) -> Result<(T, Option<JobId>), Error> {
        match &self.jobs {
            Some(jobs) => {
                let restart = JobsRestart::new(jobs);
                let done = operate(&self.manager(&restart))?;
                Ok((done, restart.submitted()))
            }
            None => Ok((operate(&self.manager(self.restart.as_ref()))?, None)),
        }
    }

    fn manager<'a>(&'a self, restart: &'a dyn Restart) -> Manager<'a> {
        Manager {
            state: &self.state,
            secrets: &self.secrets,
            config: &self.config,
            lock: &self.lock,
            placement: Placement {
                group: &self.group,
                swap: Some(&self.xray),
            },
            live: self.live.as_ref(),
            restart,
            spares: self.spares,
            lock_wait: LOCK_WAIT,
            now,
        }
    }
}

/// Serves `service` on `127.0.0.1:port` until the process stops.
pub fn serve(service: Arc<Service>, port: u16) -> Result<(), Error> {
    let runtime = Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| Error::new(format!("cannot start a runtime: {error}")))?;
    runtime.block_on(async {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, port))
            .await
            .map_err(|error| Error::new(format!("cannot listen on 127.0.0.1:{port}: {error}")))?;
        serve_on(service, listener).await
    })
}

/// [`serve`] on a listener the caller bound.
pub async fn serve_on(service: Arc<Service>, listener: TcpListener) -> Result<(), Error> {
    axum::serve(listener, router(service))
        .await
        .map_err(|error| Error::new(format!("the agent stopped: {error}")))
}
