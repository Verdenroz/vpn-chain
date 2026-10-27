use std::cell::RefCell;
use std::collections::BTreeMap;
use std::net::SocketAddr;
use std::process::Command;
use std::time::Duration;

use relay_jobs::{JobId, JobKind, Jobs};
use tokio::runtime::{Builder, Runtime};
use xray_api::{Traffic, VlessUser, XrayApi};

use crate::{Error, INBOUND_TAG, Live, Restart};

const API_TIMEOUT: Duration = Duration::from_secs(5);
const UNIT: &str = "xray";

/// The xray API on this machine's loopback, driven from a private single-thread runtime so
/// the device operations stay plain blocking calls.
///
/// Each call dials afresh: a connection kept across an xray restart fails its next call.
pub struct XrayLive {
    addr: SocketAddr,
    runtime: Runtime,
}

impl XrayLive {
    pub fn connect(port: u16) -> Result<Self, Error> {
        let runtime = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| Error::new(format!("cannot start a runtime: {error}")))?;
        Ok(Self {
            addr: SocketAddr::from(([127, 0, 0, 1], port)),
            runtime,
        })
    }

    fn api(&self) -> XrayApi {
        let _entered = self.runtime.enter();
        XrayApi::new(self.addr, API_TIMEOUT)
    }
}

impl Live for XrayLive {
    fn add_user(&self, user: VlessUser) -> Result<(), xray_api::Error> {
        self.runtime
            .block_on(self.api().add_user(INBOUND_TAG, user))
    }

    fn remove_user(&self, email: &str) -> Result<(), xray_api::Error> {
        self.runtime
            .block_on(self.api().remove_user(INBOUND_TAG, email))
    }

    fn drain_traffic(&self) -> Result<BTreeMap<String, Traffic>, xray_api::Error> {
        self.runtime.block_on(self.api().user_traffic(None, true))
    }
}

/// `systemctl restart xray`. It returns once the unit has started, not once xray serves.
pub struct Systemctl;

impl Restart for Systemctl {
    fn restart(&self) -> Result<(), Error> {
        let status = Command::new("systemctl")
            .args(["restart", UNIT])
            .status()
            .map_err(|error| Error::new(format!("systemctl: {error}")))?;
        if status.success() {
            Ok(())
        } else {
            Err(Error::new(format!(
                "systemctl restart {UNIT} failed ({status})"
            )))
        }
    }
}

/// Hands the restart to the privileged runner as a job, which outlives the caller and rolls
/// back if the relay stops working. It returns at once, before xray has restarted.
pub struct JobsRestart<'a> {
    jobs: &'a Jobs,
    submitted: RefCell<Option<JobId>>,
}

impl<'a> JobsRestart<'a> {
    pub fn new(jobs: &'a Jobs) -> Self {
        Self {
            jobs,
            submitted: RefCell::new(None),
        }
    }

    /// The job a restart was handed to, for the client to confirm once it has reconnected.
    pub fn submitted(&self) -> Option<JobId> {
        self.submitted.borrow().clone()
    }
}

impl Restart for JobsRestart<'_> {
    fn restart(&self) -> Result<(), Error> {
        let id = self
            .jobs
            .submit(JobKind::RestartXray)
            .map_err(|error| Error::new(format!("cannot queue the restart: {error}")))?;
        *self.submitted.borrow_mut() = Some(id);
        Ok(())
    }
}
