use crate::store::{self, now};
use crate::{Error, Job, JobId, JobKind, JobState, Layout, Request};

const FILE_MODE: u32 = 0o600;

/// The unprivileged side: it records a job and asks the root runner for it by dropping a request
/// file, and never changes anything xray runs.
pub struct Jobs {
    layout: Layout,
}

impl Jobs {
    pub fn new(layout: Layout) -> Self {
        Self { layout }
    }

    /// Queues `kind` and returns at once; the runner picks it up on its own, so the job outlives
    /// the caller.
    pub fn submit(&self, kind: JobKind) -> Result<JobId, Error> {
        let id = JobId::generate()?;
        let queued_at = now();
        let job = Job {
            id: id.clone(),
            kind: kind.clone(),
            state: JobState::Queued,
            reason: None,
            queued_at,
            updated_at: queued_at,
            confirm_by: None,
        };
        store::write_job(&self.layout, &job)?;
        let request = Request {
            id: id.clone(),
            kind,
        };
        store::write_atomic(
            &self.layout.requests(),
            &format!("{id}.req"),
            request.line().as_bytes(),
            FILE_MODE,
            None,
        )?;
        Ok(id)
    }

    pub fn status(&self, id: &JobId) -> Result<Job, Error> {
        store::read_job(&self.layout, id)
    }

    /// Tells the runner the relay still works for a client that reconnected through it. Accepted
    /// only while the job awaits confirm, and the job's state afterwards says whether it was in time.
    pub fn confirm(&self, id: &JobId) -> Result<(), Error> {
        let job = self.status(id)?;
        if job.state != JobState::AwaitingConfirm {
            return Err(Error::new(format!(
                "the job is {}, not awaiting confirm",
                job.state
            )));
        }
        store::write_atomic(&self.layout.confirms(), id.as_str(), b"", FILE_MODE, None)
    }
}
