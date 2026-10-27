use std::fs;
use std::io;
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use relay_jobs::runner::{Host, Runner};
use relay_jobs::{JobId, JobKind, JobState, Jobs, Layout};
use tempfile::TempDir;

const OLD: &str = "old config";
const NEW: &str = "new config";

#[derive(Default)]
struct FakeHost {
    restarts: AtomicUsize,
    /// Restarts after this many calls fail.
    restarts_before_failing: Mutex<Option<usize>>,
    /// Configs whose contents are rejected by `-test`.
    rejects: Mutex<Vec<String>>,
}

impl Host for FakeHost {
    fn restart_xray(&self) -> io::Result<()> {
        let calls = self.restarts.fetch_add(1, Ordering::SeqCst);
        match *self.restarts_before_failing.lock().unwrap() {
            Some(limit) if calls >= limit => Err(io::Error::other("restart refused")),
            _ => Ok(()),
        }
    }

    fn accepts_config(&self, _xray: &Path, config: &Path) -> io::Result<bool> {
        let text = fs::read_to_string(config)?;
        Ok(!self.rejects.lock().unwrap().contains(&text))
    }
}

struct Fixture {
    dir: TempDir,
    layout: Layout,
    host: FakeHost,
}

impl Fixture {
    fn new() -> Self {
        let dir = TempDir::new().unwrap();
        let root = dir.path();
        for name in [
            "state/jobs",
            "state/requests",
            "state/confirm",
            "state/staged",
            "state/snapshots",
            "etc",
            "xray",
        ] {
            fs::create_dir_all(root.join(name)).unwrap();
        }
        for version in ["1.0.0", "2.0.0"] {
            let binary = root.join("xray").join(version);
            fs::create_dir(&binary).unwrap();
            fs::write(binary.join("xray"), "").unwrap();
            fs::set_permissions(binary.join("xray"), fs::Permissions::from_mode(0o755)).unwrap();
        }
        symlink("1.0.0", root.join("xray/current")).unwrap();
        fs::write(root.join("etc/config.json"), OLD).unwrap();
        fs::set_permissions(
            root.join("etc/config.json"),
            fs::Permissions::from_mode(0o640),
        )
        .unwrap();
        let mut layout = Layout::new(root.join("state"));
        layout.xray_root = root.join("xray");
        layout.xray_config = root.join("etc/config.json");
        layout.confirm_window = Duration::from_secs(3);
        layout.poll = Duration::from_millis(20);
        Self {
            dir,
            layout,
            host: FakeHost::default(),
        }
    }

    fn jobs(&self) -> Jobs {
        Jobs::new(self.layout.clone())
    }

    fn config(&self) -> String {
        fs::read_to_string(&self.layout.xray_config).unwrap()
    }

    fn current(&self) -> PathBuf {
        fs::read_link(self.layout.current()).unwrap()
    }

    fn restarts(&self) -> usize {
        self.host.restarts.load(Ordering::SeqCst)
    }

    fn stage(&self, text: &str) {
        fs::write(self.layout.staged_config(), text).unwrap();
    }

    fn drain(&self) -> usize {
        Runner::new(&self.layout, &self.host).drain()
    }

    /// Submits `kind`, drains in the background, and calls `during` once the job awaits confirm.
    fn run(&self, kind: JobKind, during: impl FnOnce(&Fixture, &JobId)) -> (JobId, usize) {
        let id = self.jobs().submit(kind).unwrap();
        let refused = thread::scope(|scope| {
            let runner = scope.spawn(|| self.drain());
            self.wait_for(&id, |state| {
                state == JobState::AwaitingConfirm || state.is_terminal()
            });
            during(self, &id);
            runner.join().unwrap()
        });
        (id, refused)
    }

    fn wait_for(&self, id: &JobId, until: impl Fn(JobState) -> bool) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while !until(self.state(id)) {
            assert!(Instant::now() < deadline, "job never got there");
            thread::sleep(Duration::from_millis(10));
        }
    }

    fn state(&self, id: &JobId) -> JobState {
        self.jobs().status(id).unwrap().state
    }

    fn snapshots(&self) -> usize {
        fs::read_dir(self.layout.snapshots()).unwrap().count()
    }
}

fn swap() -> JobKind {
    JobKind::SwapXray {
        version: "2.0.0".parse().unwrap(),
    }
}

#[test]
fn confirmed_restart_keeps_everything() {
    let fixture = Fixture::new();
    let (id, refused) = fixture.run(JobKind::RestartXray, |fixture, id| {
        fixture.jobs().confirm(id).unwrap();
    });

    assert_eq!(refused, 0);
    assert_eq!(fixture.state(&id), JobState::Confirmed);
    assert_eq!(fixture.restarts(), 1);
}

#[test]
fn unconfirmed_restart_is_rolled_back_without_a_second_restart() {
    let mut fixture = Fixture::new();
    fixture.layout.confirm_window = Duration::ZERO;
    let (id, _) = fixture.run(JobKind::RestartXray, |_, _| {});

    let job = fixture.jobs().status(&id).unwrap();
    assert_eq!(job.state, JobState::RolledBack);
    assert_eq!(job.confirm_by, None);
    assert_eq!(fixture.restarts(), 1);
}

#[test]
fn confirmed_swap_keeps_the_new_version_and_drops_the_snapshot() {
    let fixture = Fixture::new();
    let (id, _) = fixture.run(swap(), |fixture, id| {
        assert_eq!(fixture.current(), Path::new("2.0.0"));
        assert_eq!(fixture.snapshots(), 1);
        fixture.jobs().confirm(id).unwrap();
    });

    assert_eq!(fixture.state(&id), JobState::Confirmed);
    assert_eq!(fixture.current(), Path::new("2.0.0"));
    assert_eq!(fixture.snapshots(), 0);
    assert!(fixture.dir.path().join("xray/1.0.0/xray").exists());
}

#[test]
fn unconfirmed_swap_returns_to_the_previous_version() {
    let mut fixture = Fixture::new();
    fixture.layout.confirm_window = Duration::from_millis(300);
    let (id, _) = fixture.run(swap(), |fixture, _| {
        assert_eq!(fixture.current(), Path::new("2.0.0"));
    });

    let job = fixture.jobs().status(&id).unwrap();
    assert_eq!(job.state, JobState::RolledBack);
    assert_eq!(fixture.current(), Path::new("1.0.0"));
    assert_eq!(fixture.config(), OLD);
    assert_eq!(fixture.restarts(), 2);
    assert_eq!(fixture.snapshots(), 0);
}

#[test]
fn unconfirmed_config_is_restored_with_its_mode() {
    let mut fixture = Fixture::new();
    fixture.layout.confirm_window = Duration::from_millis(300);
    fixture.stage(NEW);
    let (id, _) = fixture.run(JobKind::ApplyConfig, |fixture, _| {
        assert_eq!(fixture.config(), NEW);
        let mode = fs::metadata(&fixture.layout.xray_config).unwrap();
        assert_eq!(mode.permissions().mode() & 0o777, 0o640);
    });

    assert_eq!(fixture.state(&id), JobState::RolledBack);
    assert_eq!(fixture.config(), OLD);
    let mode = fs::metadata(&fixture.layout.xray_config).unwrap();
    assert_eq!(mode.permissions().mode() & 0o777, 0o640);
    assert!(!fixture.layout.staged_config().exists());
}

#[test]
fn confirmed_config_stays() {
    let fixture = Fixture::new();
    fixture.stage(NEW);
    let (id, _) = fixture.run(JobKind::ApplyConfig, |fixture, id| {
        fixture.jobs().confirm(id).unwrap();
    });

    assert_eq!(fixture.state(&id), JobState::Confirmed);
    assert_eq!(fixture.config(), NEW);
    assert_eq!(fixture.snapshots(), 0);
}

#[test]
fn a_config_xray_rejects_is_never_placed() {
    let fixture = Fixture::new();
    fixture.stage(NEW);
    fixture.host.rejects.lock().unwrap().push(NEW.to_owned());
    let (id, _) = fixture.run(JobKind::ApplyConfig, |_, _| {});

    let job = fixture.jobs().status(&id).unwrap();
    assert_eq!(job.state, JobState::Failed);
    assert_eq!(
        job.reason.as_deref(),
        Some("xray -test rejected the config")
    );
    assert_eq!(fixture.config(), OLD);
    assert_eq!(fixture.restarts(), 0);
}

#[test]
fn a_swap_to_a_version_that_is_not_installed_fails() {
    let fixture = Fixture::new();
    fs::remove_dir_all(fixture.dir.path().join("xray/2.0.0")).unwrap();
    let (id, _) = fixture.run(swap(), |_, _| {});

    assert_eq!(fixture.state(&id), JobState::Failed);
    assert_eq!(fixture.current(), Path::new("1.0.0"));
    assert_eq!(fixture.restarts(), 0);
}

#[test]
fn a_swap_whose_restart_fails_is_rolled_back_at_once() {
    let fixture = Fixture::new();
    *fixture.host.restarts_before_failing.lock().unwrap() = Some(0);
    let (id, _) = fixture.run(swap(), |_, _| {});

    let job = fixture.jobs().status(&id).unwrap();
    assert_eq!(job.state, JobState::Failed);
    assert_eq!(
        job.reason.as_deref(),
        Some("the change failed and the rollback failed")
    );
    assert_eq!(fixture.current(), Path::new("1.0.0"));
}

#[test]
fn requests_that_are_not_fixed_verbs_are_refused_and_removed() {
    let fixture = Fixture::new();
    let id = fixture.jobs().submit(JobKind::RestartXray).unwrap();
    let requests = fixture.layout.requests();
    fs::remove_file(requests.join(format!("{id}.req"))).unwrap();
    let valid = id.as_str();
    for (name, body) in [
        ("a", format!("rm-rf {valid}\n")),
        ("b", "restart-xray ../../etc/passwd\n".to_owned()),
        ("c", format!("swap-xray {valid} ../../tmp\n")),
        ("d", format!("apply-config {valid}\n")),
        ("e", format!("restart-xray {}\n", "a".repeat(32))),
    ] {
        fs::write(requests.join(format!("{name}.req")), body).unwrap();
    }
    symlink("/etc/passwd", requests.join("f.req")).unwrap();
    fs::write(requests.join("g.req"), "x".repeat(4096)).unwrap();

    assert_eq!(fixture.drain(), 7);

    assert_eq!(fs::read_dir(&requests).unwrap().count(), 0);
    assert_eq!(fixture.state(&id), JobState::Failed);
    assert_eq!(fixture.restarts(), 0);
}

#[test]
fn a_job_the_runner_died_in_is_rolled_back_by_the_next_run() {
    let fixture = Fixture::new();
    let id = fixture.jobs().submit(swap()).unwrap();
    fs::remove_file(fixture.layout.requests().join(format!("{id}.req"))).unwrap();
    let snapshot = fixture.layout.snapshots().join(id.as_str());
    fs::create_dir(&snapshot).unwrap();
    fs::write(snapshot.join("config"), OLD).unwrap();
    fs::write(snapshot.join("version"), "1.0.0").unwrap();
    fs::write(&fixture.layout.xray_config, NEW).unwrap();
    fs::remove_file(fixture.layout.current()).unwrap();
    symlink("2.0.0", fixture.layout.current()).unwrap();
    let record = fixture.layout.jobs().join(format!("{id}.json"));
    let text = fs::read_to_string(&record).unwrap();
    fs::write(
        &record,
        text.replace("\"state\": \"queued\"", "\"state\": \"awaiting-confirm\""),
    )
    .unwrap();

    assert_eq!(fixture.drain(), 0);

    assert_eq!(fixture.state(&id), JobState::RolledBack);
    assert_eq!(fixture.config(), OLD);
    assert_eq!(fixture.current(), Path::new("1.0.0"));
    assert_eq!(fixture.restarts(), 1);
    assert_eq!(fixture.snapshots(), 0);
}

#[test]
fn a_request_that_misnames_its_job_fails_that_job() {
    let fixture = Fixture::new();
    let id = fixture.jobs().submit(JobKind::RestartXray).unwrap();
    fs::write(
        fixture.layout.requests().join(format!("{id}.req")),
        format!("apply-config {id}\n"),
    )
    .unwrap();

    assert_eq!(fixture.drain(), 1);

    assert_eq!(fixture.state(&id), JobState::Failed);
    assert_eq!(fixture.restarts(), 0);
}

#[test]
fn a_swap_cannot_start_when_current_is_not_a_version_link() {
    let fixture = Fixture::new();
    fs::remove_file(fixture.layout.current()).unwrap();
    symlink(
        fixture.dir.path().join("xray/1.0.0"),
        fixture.layout.current(),
    )
    .unwrap();
    let (id, _) = fixture.run(swap(), |_, _| {});

    assert_eq!(fixture.state(&id), JobState::Failed);
    assert_eq!(fixture.restarts(), 0);
}

#[test]
fn a_restart_that_fails_says_so() {
    let fixture = Fixture::new();
    *fixture.host.restarts_before_failing.lock().unwrap() = Some(0);
    let (id, _) = fixture.run(JobKind::RestartXray, |_, _| {});

    let job = fixture.jobs().status(&id).unwrap();
    assert_eq!(job.state, JobState::Failed);
    assert_eq!(job.reason.as_deref(), Some("the restart failed"));
}

#[test]
fn a_failed_rollback_keeps_its_snapshot_for_the_next_run() {
    let fixture = Fixture::new();
    *fixture.host.restarts_before_failing.lock().unwrap() = Some(0);
    let (id, _) = fixture.run(swap(), |_, _| {});
    assert_eq!(fixture.state(&id), JobState::Failed);
    assert_eq!(fixture.snapshots(), 1);

    *fixture.host.restarts_before_failing.lock().unwrap() = None;
    assert_eq!(fixture.drain(), 0);

    assert_eq!(fixture.state(&id), JobState::RolledBack);
    assert_eq!(fixture.snapshots(), 0);
}
