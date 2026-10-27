use std::path::PathBuf;

use relayd::{DEVICES_LOCK, Error, layout, pairing, secrets_path};

use crate::args::Args;

pub fn pair(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &["device", "state", "secrets", "lock"], &[])?;
    let [] = args.words()?;
    let device = args.value("device");
    let state = args
        .path("state")
        .unwrap_or_else(|| PathBuf::from(layout::STATE));
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&state));
    let lock = args
        .path("lock")
        .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK));
    Some(
        pairing::issue(&state, &secrets, &lock, device.as_deref())
            .map(|link| print!("{}", pairing::show(&link))),
    )
}
