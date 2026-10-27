use std::collections::BTreeMap;
use std::net::SocketAddr;
use std::process::Command;
use std::time::Duration;

use tokio::runtime::{Builder, Runtime};
use xray_api::{Traffic, VlessUser, XrayApi};

use crate::{Error, INBOUND_TAG, Live, Restart};

const API_TIMEOUT: Duration = Duration::from_secs(5);
const UNIT: &str = "xray";

/// The xray API on this machine's loopback, driven from a private single-thread runtime so
/// the device operations stay plain blocking calls.
pub struct XrayLive {
    api: XrayApi,
    runtime: Runtime,
}

impl XrayLive {
    pub fn connect(port: u16) -> Result<Self, Error> {
        let runtime = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| Error::new(format!("cannot start a runtime: {error}")))?;
        let api = {
            let _entered = runtime.enter();
            XrayApi::new(SocketAddr::from(([127, 0, 0, 1], port)), API_TIMEOUT)
        };
        Ok(Self { api, runtime })
    }
}

impl Live for XrayLive {
    fn add_user(&self, user: VlessUser) -> Result<(), xray_api::Error> {
        self.runtime.block_on(self.api.add_user(INBOUND_TAG, user))
    }

    fn remove_user(&self, email: &str) -> Result<(), xray_api::Error> {
        self.runtime
            .block_on(self.api.remove_user(INBOUND_TAG, email))
    }

    fn drain_traffic(&self) -> Result<BTreeMap<String, Traffic>, xray_api::Error> {
        self.runtime.block_on(self.api.user_traffic(None, true))
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
