use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use relay_jobs::{Jobs, Layout};
use relayd::agent::{Audit, Enroll, Service, Verifier, serve as serve_agent};
use relayd::{Error, Relay, Role, Systemctl, XrayLive, add_key, secrets_path};

use crate::args::Args;
use crate::target::{Target, valued};

const LOCK_WAIT: Duration = Duration::from_secs(30);

pub fn serve(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(args, &valued(&["audit", "jobs"]), &[])?;
    let [] = args.words()?;
    let audit = args.path("audit");
    let jobs = args.path("jobs");
    let target = Target::from(&mut args)?;
    Some(run(target, audit, jobs))
}

fn run(target: Target, audit: Option<PathBuf>, jobs: Option<PathBuf>) -> Result<(), Error> {
    let relay = Relay::load(&target.state, &target.secrets)?;
    let api = relay.state.api.as_ref().ok_or_else(|| {
        Error::new("this relay has no stats API, so its devices cannot be changed live")
    })?;
    let audit = audit.unwrap_or_else(|| target.state.with_file_name("audit.jsonl"));
    let enroll = Enroll::beside(&target.state);
    let service = Service {
        state: target.state,
        secrets: target.secrets,
        config: target.config,
        lock: target.lock,
        xray: target.xray,
        group: target.group,
        spares: target.spares,
        live: Arc::new(XrayLive::connect(api.port)?),
        restart: Arc::new(Systemctl),
        jobs: jobs.map(|dir| Jobs::new(Layout::new(dir))),
        audit: Audit::open(&audit)?,
        verifier: Verifier::default(),
        enroll,
    };
    serve_agent(Arc::new(service), relay.state.agent.port)
}

pub fn keys(args: &[&str]) -> Option<Result<(), Error>> {
    let ["add", rest @ ..] = args else {
        return None;
    };
    let mut args = Args::parse(
        rest,
        &["state", "secrets", "lock", "role", "public-key"],
        &[],
    )?;
    let [device] = args.words()?;
    let device = device.to_owned();
    let role = match args.value("role").as_deref() {
        None | Some("member") => Role::Member,
        Some("admin") => Role::Admin,
        Some(_) => return None,
    };
    let public_key = args.path("public-key")?;
    let state = args.path("state")?;
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&state));
    let lock = args
        .path("lock")
        .unwrap_or_else(|| PathBuf::from(relayd::DEVICES_LOCK));
    Some(
        fs::read(&public_key)
            .map_err(|error| Error::at(&public_key, error))
            .and_then(|bytes| add_key(&state, &secrets, &lock, LOCK_WAIT, &device, role, &bytes))
            .map(|key| println!("added key {} for {device}", key.id)),
    )
}
