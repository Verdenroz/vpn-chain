use std::path::{Path, PathBuf};
use std::time::Duration;

use relayd::{
    DEVICES_LOCK, Error, Manager, Placement, Relay, Role, SPARES, Systemctl, XrayLive, install,
    layout, now, pairing, secrets_path,
};

use crate::args::Args;
use crate::target::XRAY_GROUP;

const LOCK_WAIT: Duration = Duration::from_secs(30);

pub fn pair(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(
        args,
        &["device", "new", "role", "state", "secrets", "lock"],
        &[],
    )?;
    let [] = args.words()?;
    let device = args.value("device");
    let new = args.value("new");
    let role = args.value("role");
    let role = match role.as_deref() {
        None | Some("member") => Role::Member,
        Some("admin") if new.is_some() => Role::Admin,
        _ => return None,
    };
    if device.is_some() && new.is_some() {
        return None;
    }
    let state = args
        .path("state")
        .unwrap_or_else(|| PathBuf::from(layout::STATE));
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&state));
    let lock = args
        .path("lock")
        .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK));
    let paired = match (&new, &device) {
        (Some(name), _) => add(&state, &secrets, &lock, name, role).map(|()| Some(name.as_str())),
        (None, Some(name)) => Ok(Some(name.as_str())),
        (None, None) => {
            eprintln!(
                "relayd: warning: this pairs the first admin device again, which signs out the app using it; `relayd pair --new <name>` adds a device instead"
            );
            Ok(None)
        }
    };
    Some(paired.and_then(|name| {
        let link = pairing::issue(&state, &secrets, &lock, name)?;
        print!("{}", pairing::show(&link));
        Ok(())
    }))
}

/// Adds `name` from the spare pool through xray's API, so no restart and no device is dropped.
fn add(state: &Path, secrets: &Path, lock: &Path, name: &str, role: Role) -> Result<(), Error> {
    let relay = Relay::load(state, secrets)?;
    if relay.state.spare_short_ids.is_empty() {
        return Err(Error::conflict(
            "no spare short IDs are left, and adding a device then needs an xray restart: run `relayd maintenance refill-pool` first",
        ));
    }
    let api = relay.state.api.as_ref().ok_or_else(|| {
        Error::new("this relay has no stats API, so its devices cannot be changed live")
    })?;
    let live = XrayLive::connect(api.port)?;
    let config = Path::new(layout::XRAY_CONFIG);
    Manager {
        state,
        secrets,
        config,
        lock,
        placement: Placement {
            group: XRAY_GROUP,
            swap: Some(Path::new(layout::XRAY)),
        },
        live: &live,
        restart: &Systemctl,
        spares: SPARES,
        lock_wait: LOCK_WAIT,
        now,
    }
    .issue(name, role, false, None)?;
    install::own(config)
}
