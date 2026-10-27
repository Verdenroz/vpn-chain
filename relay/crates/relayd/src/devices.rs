use std::collections::BTreeMap;
use std::fs;
use std::path::Path;
use std::time::Duration;

use uuid::Uuid;
use xray_api::{Traffic, UserId, VlessUser};

use crate::identity::Identity;
use crate::live::fold;
use crate::{
    Device, Error, Live, Placement, Relay, Restart, Role, Secret, State, store, valid_name,
    write_config,
};

/// Short IDs kept unissued in the config, so a new device never needs a restart.
pub const SPARES: usize = 8;

/// Device operations on one relay. Every operation takes the devices lock first, so it never
/// interleaves with the server scripts or another relayd, and fails if the lock stays held for
/// `lock_wait`.
///
/// Each change is saved to the state, rendered to the config through the checked swap, and
/// applied to the running xray through its API; only [`Manager::revoke`], [`Manager::refill_pool`]
/// and an [`Manager::add`] that refills an empty pool restart it.
pub struct Manager<'a> {
    pub state: &'a Path,
    pub secrets: &'a Path,
    pub config: &'a Path,
    pub lock: &'a Path,
    pub placement: Placement<'a>,
    pub live: &'a dyn Live,
    pub restart: &'a dyn Restart,
    pub spares: usize,
    pub lock_wait: Duration,
    pub now: fn() -> String,
}

impl Manager<'_> {
    /// Issues a device from the spare pool and writes its identity to `out`. With an empty
    /// pool it refuses, or with `allow_restart` refills the pool and restarts xray.
    pub fn add(
        &self,
        name: &str,
        role: Role,
        out: &Path,
        allow_restart: bool,
    ) -> Result<(), Error> {
        if !valid_name(name) {
            return Err(Error::new(format!("invalid device name: {name}")));
        }
        let _held = store::lock_within(self.lock, self.lock_wait)?;
        let relay = self.begin()?;
        if relay.state.devices.iter().any(|device| device.name == name) {
            return Err(Error::new(format!("device {name} already exists")));
        }
        let drained = relay.state.spare_short_ids.is_empty();
        if drained && !allow_restart {
            return Err(Error::new(
                "no spare short IDs left; run again with --allow-restart to refill the pool and restart xray",
            ));
        }
        let (short_id, spares) = match relay.state.spare_short_ids.split_first() {
            Some((first, rest)) => (first.clone(), rest.to_vec()),
            None => (new_short_id(), Vec::new()),
        };
        let device = Device {
            name: name.to_owned(),
            uuid: new_uuid(),
            short_id,
            role,
            added: (self.now)(),
            up: 0,
            down: 0,
            seen: None,
        };
        let spares = if drained {
            topped_up(spares, self.spares)
        } else {
            spares
        };
        let next = changed(&relay, with(&relay.state.devices, device.clone()), spares);
        Identity::of(&relay, &device).create(out)?;
        let written = if drained {
            self.write_out(&relay, &next)
        } else {
            self.apply_live(&relay, &next, || self.add_user(vless_user(&device)))
        };
        if written.is_err() {
            let _ = fs::remove_file(out);
        }
        written?;
        if drained {
            self.restart_xray()?;
        }
        Ok(())
    }

    /// Gives the device `old` the name `new`, keeping its UUID and short ID.
    pub fn rename(&self, old: &str, new: &str) -> Result<(), Error> {
        if !valid_name(new) {
            return Err(Error::new(format!("invalid device name: {new}")));
        }
        let _held = store::lock_within(self.lock, self.lock_wait)?;
        let relay = self.begin()?;
        let device = find(&relay, old)?;
        if relay.state.devices.iter().any(|device| device.name == new) {
            return Err(Error::new(format!("device {new} already exists")));
        }
        let renamed = Device {
            name: new.to_owned(),
            ..device.clone()
        };
        let next = changed(
            &relay,
            replacing(&relay.state.devices, old, renamed.clone()),
            relay.state.spare_short_ids.clone(),
        );
        self.apply_live(&relay, &next, || self.replace_user(device, &renamed))
    }

    /// Gives the device a new UUID and a short ID from the pool, and writes them to `out`.
    /// The old pair stops working at once.
    pub fn rekey(&self, name: &str, out: &Path) -> Result<(), Error> {
        let _held = store::lock_within(self.lock, self.lock_wait)?;
        let relay = self.begin()?;
        let device = find(&relay, name)?;
        let Some((short_id, spares)) = relay.state.spare_short_ids.split_first() else {
            return Err(Error::new(
                "no spare short IDs left; run: relayd maintenance refill-pool",
            ));
        };
        let rekeyed = Device {
            uuid: new_uuid(),
            short_id: short_id.clone(),
            ..device.clone()
        };
        let next = changed(
            &relay,
            replacing(&relay.state.devices, name, rekeyed.clone()),
            spares.to_vec(),
        );
        Identity::of(&relay, &rekeyed).create(out)?;
        let applied = self.apply_live(&relay, &next, || self.replace_user(device, &rekeyed));
        if applied.is_err() {
            let _ = fs::remove_file(out);
        }
        applied
    }

    /// Removes the device, refills the pool and restarts xray, since an open connection
    /// survives the removal of its user until then. The last admin stays unless `force`.
    ///
    /// A revocation goes ahead when xray's API cannot be read, because it must work when
    /// xray is the problem. Returns whether the counters since the last fold were lost then.
    pub fn revoke(&self, name: &str, force: bool) -> Result<bool, Error> {
        let _held = store::lock_within(self.lock, self.lock_wait)?;
        let (relay, counters_lost) = self.begin_lenient()?;
        let device = find(&relay, name)?;
        let admins = relay
            .state
            .devices
            .iter()
            .filter(|device| device.role == Role::Admin)
            .count();
        if device.role == Role::Admin && admins == 1 && !force {
            return Err(Error::new(format!(
                "{name} is the last admin; revoking it leaves no one to manage the relay (--force to revoke it anyway)"
            )));
        }
        let devices = relay
            .state
            .devices
            .iter()
            .filter(|device| device.name != name)
            .cloned()
            .collect();
        let spares = topped_up(relay.state.spare_short_ids.clone(), self.spares);
        self.write_out(&relay, &changed(&relay, devices, spares))?;
        self.restart_xray()?;
        Ok(counters_lost)
    }

    /// Fills the pool back to its size and restarts xray to load the saved config, which also
    /// finishes a revocation or refill whose restart failed. Returns how many short IDs it added.
    pub fn refill_pool(&self) -> Result<usize, Error> {
        let _held = store::lock_within(self.lock, self.lock_wait)?;
        let relay = self.begin()?;
        let spares = topped_up(relay.state.spare_short_ids.clone(), self.spares);
        let added = spares.len() - relay.state.spare_short_ids.len();
        let next = changed(&relay, relay.state.devices.clone(), spares);
        self.write_out(&relay, &next)?;
        self.restart_xray()?;
        Ok(added)
    }

    /// Loads the relay and folds xray's counters into it, saving the totals before anything
    /// else changes. xray zeroes a counter as it reads it, so bytes are lost if the process
    /// dies, or the response times out, between that read and the save.
    fn begin(&self) -> Result<Relay, Error> {
        let relay = Relay::load(self.state, self.secrets)?;
        let traffic = self.live.drain_traffic().map_err(api_error)?;
        self.folded(relay, &traffic)
    }

    /// [`Manager::begin`] that carries on without the counters when xray's API fails.
    fn begin_lenient(&self) -> Result<(Relay, bool), Error> {
        let relay = Relay::load(self.state, self.secrets)?;
        match self.live.drain_traffic() {
            Ok(traffic) => Ok((self.folded(relay, &traffic)?, false)),
            Err(_) => Ok((relay, true)),
        }
    }

    fn folded(&self, relay: Relay, traffic: &BTreeMap<String, Traffic>) -> Result<Relay, Error> {
        let devices = fold(&relay.state.devices, traffic, &(self.now)());
        let folded = changed(&relay, devices, relay.state.spare_short_ids.clone());
        folded.save_state(self.state)?;
        Ok(folded)
    }

    /// Saves and renders `after`, then runs `step` against xray. If the step fails, `before`
    /// goes back into the state and the config.
    fn apply_live(
        &self,
        before: &Relay,
        after: &Relay,
        step: impl FnOnce() -> Result<(), xray_api::Error>,
    ) -> Result<(), Error> {
        self.write_out(before, after)?;
        step().map_err(|error| {
            let error = api_error(error);
            match self.write_out(after, before) {
                Ok(()) => error,
                Err(undo) => Error::new(format!(
                    "{error}; restoring the previous state failed: {undo}"
                )),
            }
        })
    }

    fn restart_xray(&self) -> Result<(), Error> {
        self.restart.restart().map_err(|error| {
            Error::new(format!(
                "{error}; the change is saved but xray still runs the old config: run `relayd maintenance refill-pool` or restart it"
            ))
        })
    }

    /// The state is saved first, so a crash after the config swap never leaves a config that
    /// the state does not describe. A rejected config puts the old state back.
    fn write_out(&self, before: &Relay, after: &Relay) -> Result<(), Error> {
        after.save_state(self.state)?;
        write_config(after, self.config, &self.placement).inspect_err(|_| {
            let _ = before.save_state(self.state);
        })
    }

    /// xray refuses a second user under an email it already holds, so the old user goes first.
    fn replace_user(&self, old: &Device, new: &Device) -> Result<(), xray_api::Error> {
        self.remove_user(&old.name)?;
        self.add_user(vless_user(new)).inspect_err(|_| {
            let _ = self.live.add_user(vless_user(old));
        })
    }

    /// A timeout may arrive after xray applied the call, so a second attempt that finds it
    /// already done counts as success.
    fn add_user(&self, user: VlessUser) -> Result<(), xray_api::Error> {
        match self.live.add_user(user.clone()) {
            Err(xray_api::Error::Timeout(_)) => match self.live.add_user(user) {
                Err(xray_api::Error::UserExists(_)) => Ok(()),
                other => other,
            },
            other => other,
        }
    }

    fn remove_user(&self, email: &str) -> Result<(), xray_api::Error> {
        match self.live.remove_user(email) {
            Err(xray_api::Error::Timeout(_)) => match self.live.remove_user(email) {
                Err(xray_api::Error::UserNotFound(_)) => Ok(()),
                other => other,
            },
            other => other,
        }
    }
}

fn find<'a>(relay: &'a Relay, name: &str) -> Result<&'a Device, Error> {
    relay
        .state
        .devices
        .iter()
        .find(|device| device.name == name)
        .ok_or_else(|| Error::new(format!("no device named {name}")))
}

fn changed(relay: &Relay, devices: Vec<Device>, spare_short_ids: Vec<Secret>) -> Relay {
    Relay {
        state: State {
            devices,
            spare_short_ids,
            ..relay.state.clone()
        },
        secrets: relay.secrets.clone(),
    }
}

fn with(devices: &[Device], added: Device) -> Vec<Device> {
    devices.iter().cloned().chain([added]).collect()
}

fn replacing(devices: &[Device], name: &str, device: Device) -> Vec<Device> {
    devices
        .iter()
        .map(|held| {
            if held.name == name {
                device.clone()
            } else {
                held.clone()
            }
        })
        .collect()
}

fn topped_up(spares: Vec<Secret>, size: usize) -> Vec<Secret> {
    let missing = size.saturating_sub(spares.len());
    spares
        .into_iter()
        .chain((0..missing).map(|_| new_short_id()))
        .collect()
}

fn vless_user(device: &Device) -> VlessUser {
    VlessUser {
        email: device.name.clone(),
        id: device
            .uuid
            .expose()
            .parse::<UserId>()
            .expect("a valid relay holds canonical UUIDs"),
        level: 0,
    }
}

fn new_uuid() -> Secret {
    Secret::new(Uuid::new_v4().to_string())
}

fn new_short_id() -> Secret {
    let mut bytes = [0u8; 8];
    getrandom::fill(&mut bytes).expect("the OS provides randomness");
    Secret::new(
        bytes
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>(),
    )
}

fn api_error(error: xray_api::Error) -> Error {
    Error::new(format!("xray API: {error}"))
}
