use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use relay_jobs::{Job, JobId, JobKind, JobState, Jobs, Layout, Request};
use tempfile::TempDir;

const ID: &str = "0123456789abcdef0123456789abcdef";

fn layout() -> (TempDir, Layout) {
    let dir = TempDir::new().unwrap();
    for name in ["jobs", "requests", "confirm"] {
        fs::create_dir(dir.path().join(name)).unwrap();
    }
    let layout = Layout::new(dir.path());
    (dir, layout)
}

fn mode(path: &Path) -> u32 {
    fs::metadata(path).unwrap().permissions().mode() & 0o777
}

fn id() -> JobId {
    ID.to_owned().try_into().unwrap()
}

#[test]
fn submit_records_a_private_queued_job_and_a_request() {
    let (_dir, layout) = layout();
    let jobs = Jobs::new(layout.clone());

    let id = jobs
        .submit(JobKind::SwapXray {
            version: "26.3.27".parse().unwrap(),
        })
        .unwrap();

    assert_eq!(jobs.status(&id).unwrap().state, JobState::Queued);
    assert_eq!(mode(&layout.jobs().join(format!("{id}.json"))), 0o600);
    let request = layout.requests().join(format!("{id}.req"));
    assert_eq!(mode(&request), 0o600);
    assert_eq!(
        fs::read_to_string(request).unwrap(),
        format!("swap-xray {id} 26.3.27\n")
    );
}

#[test]
fn confirm_is_accepted_only_while_awaiting() {
    let (_dir, layout) = layout();
    let jobs = Jobs::new(layout.clone());
    let id = jobs.submit(JobKind::RestartXray).unwrap();

    assert!(jobs.confirm(&id).is_err());
    assert!(!layout.confirms().join(id.as_str()).exists());

    let path = layout.jobs().join(format!("{id}.json"));
    let mut job: Job = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    job.state = JobState::AwaitingConfirm;
    fs::write(&path, serde_json::to_vec(&job).unwrap()).unwrap();

    jobs.confirm(&id).unwrap();
    assert!(layout.confirms().join(id.as_str()).exists());
}

#[test]
fn status_of_an_unknown_job_is_an_error() {
    let (_dir, layout) = layout();
    assert!(Jobs::new(layout).status(&id()).is_err());
}

#[test]
fn a_record_naming_another_job_is_refused() {
    let (_dir, layout) = layout();
    let jobs = Jobs::new(layout.clone());
    let submitted = jobs.submit(JobKind::RestartXray).unwrap();
    fs::rename(
        layout.jobs().join(format!("{submitted}.json")),
        layout.jobs().join(format!("{ID}.json")),
    )
    .unwrap();

    assert!(jobs.status(&id()).is_err());
}

#[test]
fn request_lines_round_trip() {
    for kind in [
        JobKind::RestartXray,
        JobKind::ApplyConfig,
        JobKind::SwapXray {
            version: "26.10.21".parse().unwrap(),
        },
    ] {
        let request = Request { id: id(), kind };
        assert_eq!(Request::parse(&request.line()).unwrap(), request);
    }
}

#[test]
fn requests_outside_the_fixed_verbs_are_refused() {
    let refused = [
        format!("rm-rf {ID}\n"),
        "restart-xray ../../etc/passwd\n".to_owned(),
        format!("restart-xray {}\n", ID.to_uppercase()),
        format!("restart-xray {ID}"),
        format!("restart-xray {ID} extra\n"),
        format!("restart-xray  {ID}\n"),
        format!("swap-xray {ID}\n"),
        format!("swap-xray {ID} ../../tmp/x\n"),
        format!("swap-xray {ID} 26.3\n"),
        format!("swap-xray {ID} 26.3.27 extra\n"),
        format!("apply-config {ID} /etc/shadow\n"),
        format!("restart-xray {ID}\nrestart-xray {ID}\n"),
        String::new(),
    ];
    for text in refused {
        assert!(Request::parse(&text).is_err(), "{text:?}");
    }
}
