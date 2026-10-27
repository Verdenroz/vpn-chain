//! The root side: performs the requests the unprivileged side dropped, one at a time, and rolls a
//! change back unless a client confirms that it can still reach the relay through it.

mod files;
mod host;

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

use crate::store::{self, now};
use crate::{Error, Job, JobId, JobKind, JobState, Layout, Request};
use files::Snapshot;
pub use host::{Host, Systemd};

const REQUEST_LIMIT: u64 = 256;
const REQUEST_SUFFIX: &str = ".req";

pub struct Runner<'a, H> {
    layout: &'a Layout,
    host: &'a H,
}

impl<'a, H: Host> Runner<'a, H> {
    pub fn new(layout: &'a Layout, host: &'a H) -> Self {
        Self { layout, host }
    }

    /// Runs every pending request, oldest first, and returns how many it refused or could not
    /// finish. A request is removed once handled, so a refused one is not retried.
    pub fn drain(&self) -> usize {
        let mut refused = match self.recover() {
            Ok(()) => 0,
            Err(error) => {
                eprintln!("relay-privileged: recovery: {error}");
                1
            }
        };
        while let Some(path) = self.next_request() {
            if let Err(error) = self.process(&path) {
                eprintln!("relay-privileged: refused {:?}: {error}", path.file_name());
                refused += 1;
            }
            let _ = fs::remove_file(&path);
        }
        refused
    }

    fn next_request(&self) -> Option<PathBuf> {
        let mut pending: Vec<_> = fs::read_dir(self.layout.requests())
            .ok()?
            .flatten()
            .filter(|entry| {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                name.ends_with(REQUEST_SUFFIX) && !name.starts_with('.')
            })
            .map(|entry| {
                let modified = entry.metadata().and_then(|meta| meta.modified()).ok();
                (modified, entry.path())
            })
            .collect();
        pending.sort();
        pending.into_iter().next().map(|(_, path)| path)
    }

    fn process(&self, path: &Path) -> Result<(), Error> {
        let bytes = store::read_regular(path, REQUEST_LIMIT)?;
        let text =
            String::from_utf8(bytes).map_err(|_| Error::at(path, "a request is plain text"))?;
        let request = Request::parse(&text)?;
        let mut job = store::read_job(self.layout, &request.id)?;
        if job.state != JobState::Queued {
            return Err(Error::new("the job is not queued"));
        }
        if job.kind != request.kind {
            let reason = "the request does not match the job";
            self.mark(&mut job, JobState::Failed, Some(reason))?;
            return Err(Error::new(reason));
        }
        self.run(job)
    }

    fn run(&self, mut job: Job) -> Result<(), Error> {
        self.mark(&mut job, JobState::Running, None)?;
        let pending = match self.prepare(&job.kind) {
            Ok(pending) => pending,
            Err(reason) => return self.mark(&mut job, JobState::Failed, Some(reason)),
        };
        let snapshot = match job.kind.changes_files() {
            true => match Snapshot::take(self.layout, &job.id) {
                Ok(snapshot) => Some(snapshot),
                Err(error) => {
                    eprintln!("relay-privileged: snapshot: {error}");
                    drop_pending(pending.as_deref());
                    return self.mark(
                        &mut job,
                        JobState::Failed,
                        Some("no snapshot to roll back to"),
                    );
                }
            },
            false => None,
        };
        if let Err(error) = self.apply(&job.kind, pending.as_deref()) {
            eprintln!("relay-privileged: apply: {error}");
            drop_pending(pending.as_deref());
            let reason = match (snapshot.is_some(), self.roll_back(snapshot)) {
                (false, _) => "the restart failed",
                (true, Ok(())) => "the change failed and was rolled back",
                (true, Err(_)) => "the change failed and the rollback failed",
            };
            return self.mark(&mut job, JobState::Failed, Some(reason));
        }
        self.await_confirm(job, snapshot)
    }

    /// Everything that can be refused before anything changes. A config is staged next to the
    /// live one here and checked by the xray that will run it.
    fn prepare(&self, kind: &JobKind) -> Result<Option<PathBuf>, &'static str> {
        let layout = self.layout;
        match kind {
            JobKind::RestartXray => Ok(None),
            JobKind::SwapXray { version } => {
                if !files::installed(layout, version) {
                    return Err("that xray version is not installed");
                }
                let xray = layout.xray_binary(version);
                self.accepts(&xray, &layout.xray_config)?;
                Ok(None)
            }
            JobKind::ApplyConfig => {
                let staged = files::read_staged(layout).map_err(|_| "no usable staged config")?;
                let pending =
                    files::stage_config(layout, &staged).map_err(|_| "cannot stage the config")?;
                let xray = layout.current().join("xray");
                self.accepts(&xray, &pending).inspect_err(|_| {
                    let _ = fs::remove_file(&pending);
                })?;
                Ok(Some(pending))
            }
        }
    }

    fn accepts(&self, xray: &Path, config: &Path) -> Result<(), &'static str> {
        match self.host.accepts_config(xray, config) {
            Ok(true) => Ok(()),
            Ok(false) => Err("xray -test rejected the config"),
            Err(_) => Err("cannot run xray -test"),
        }
    }

    fn apply(&self, kind: &JobKind, pending: Option<&Path>) -> Result<(), Error> {
        match (kind, pending) {
            (JobKind::SwapXray { version }, _) => files::point_current(self.layout, version)?,
            (JobKind::ApplyConfig, Some(pending)) => {
                files::place_config(self.layout, pending)?;
                let _ = fs::remove_file(self.layout.staged_config());
            }
            _ => {}
        }
        self.restart()
    }

    fn restart(&self) -> Result<(), Error> {
        self.host
            .restart_xray()
            .map_err(|error| Error::new(error.to_string()))
    }

    fn roll_back(&self, snapshot: Option<Snapshot>) -> Result<(), Error> {
        let Some(snapshot) = snapshot else {
            return Ok(());
        };
        let restored = snapshot.restore(self.layout).and_then(|()| self.restart());
        if restored.is_ok() {
            snapshot.discard();
        }
        restored
    }

    fn await_confirm(&self, mut job: Job, snapshot: Option<Snapshot>) -> Result<(), Error> {
        let window = self.layout.confirm_window;
        job.confirm_by = Some(now() + window.as_secs());
        self.mark(&mut job, JobState::AwaitingConfirm, None)?;
        let confirm = self.layout.confirms().join(job.id.as_str());
        let deadline = Instant::now() + window;
        let confirmed = loop {
            if confirm.symlink_metadata().is_ok() {
                break true;
            }
            if Instant::now() >= deadline {
                break false;
            }
            std::thread::sleep(self.layout.poll);
        };
        let _ = fs::remove_file(&confirm);
        if confirmed {
            snapshot.into_iter().for_each(Snapshot::discard);
            return self.mark(&mut job, JobState::Confirmed, None);
        }
        match self.roll_back(snapshot) {
            Ok(()) => self.mark(
                &mut job,
                JobState::RolledBack,
                Some("not confirmed within the window"),
            ),
            Err(error) => {
                eprintln!("relay-privileged: rollback: {error}");
                self.mark(&mut job, JobState::Failed, Some("the rollback failed"))
            }
        }
    }

    /// A runner that died or lost power mid-job leaves it running or awaiting confirm. Nobody
    /// confirmed it, so it is rolled back like any other unconfirmed change. A failed rollback
    /// keeps its snapshot and is tried again here.
    fn recover(&self) -> Result<(), Error> {
        let jobs = self.layout.jobs();
        let mut unrecorded = 0;
        for entry in fs::read_dir(&jobs)
            .map_err(|error| Error::at(&jobs, error))?
            .flatten()
        {
            let name = entry.file_name();
            let Some(id) = name
                .to_str()
                .and_then(|name| name.strip_suffix(".json"))
                .and_then(|stem| JobId::try_from(stem.to_owned()).ok())
            else {
                continue;
            };
            let Ok(mut job) = store::read_job(self.layout, &id) else {
                continue;
            };
            let snapshot = Snapshot::find(self.layout, &id);
            let stuck = match job.state {
                JobState::Running | JobState::AwaitingConfirm => true,
                JobState::Failed => snapshot.is_some(),
                _ => false,
            };
            if !stuck {
                continue;
            }
            let (state, reason) = match self.roll_back(snapshot) {
                Ok(()) => (JobState::RolledBack, "the runner stopped before a confirm"),
                Err(_) => (JobState::Failed, "the rollback failed"),
            };
            if let Err(error) = self.mark(&mut job, state, Some(reason)) {
                eprintln!("relay-privileged: recovery: {error}");
                unrecorded += 1;
            }
        }
        match unrecorded {
            0 => Ok(()),
            _ => Err(Error::new("a recovered job could not be recorded")),
        }
    }

    fn mark(&self, job: &mut Job, state: JobState, reason: Option<&str>) -> Result<(), Error> {
        job.state = state;
        job.reason = reason.map(str::to_owned);
        job.updated_at = now();
        if state != JobState::AwaitingConfirm {
            job.confirm_by = None;
        }
        store::write_job(self.layout, job)
    }
}

fn drop_pending(pending: Option<&Path>) {
    if let Some(pending) = pending {
        let _ = fs::remove_file(pending);
    }
}
