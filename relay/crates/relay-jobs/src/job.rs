use std::fmt;
use std::fs::File;
use std::io::Read;

use serde::{Deserialize, Serialize};

use crate::Error;

const ID_BYTES: usize = 16;
const VERSION_FIELDS: usize = 3;
const VERSION_DIGITS: usize = 6;

/// 32 lowercase hex digits. Every path built from a job is built from one of these, so nothing
/// read from a request or a job file can steer a path elsewhere.
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct JobId(String);

impl JobId {
    pub fn generate() -> Result<Self, Error> {
        let mut bytes = [0; ID_BYTES];
        File::open("/dev/urandom")
            .and_then(|mut random| random.read_exact(&mut bytes))
            .map_err(|error| Error::new(format!("no random bytes for a job id: {error}")))?;
        Ok(Self(
            bytes.iter().map(|byte| format!("{byte:02x}")).collect(),
        ))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for JobId {
    type Error = Error;

    fn try_from(text: String) -> Result<Self, Error> {
        let valid = text.len() == ID_BYTES * 2
            && text
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte));
        if valid {
            Ok(Self(text))
        } else {
            Err(Error::new("a job id is 32 lowercase hex digits"))
        }
    }
}

impl From<JobId> for String {
    fn from(id: JobId) -> Self {
        id.0
    }
}

impl fmt::Display for JobId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// An xray release as the directory name under the xray root, such as `26.3.27`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct Version(String);

impl Version {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for Version {
    type Error = Error;

    fn try_from(text: String) -> Result<Self, Error> {
        let fields: Vec<&str> = text.split('.').collect();
        let valid = fields.len() == VERSION_FIELDS
            && fields.iter().all(|field| {
                (1..=VERSION_DIGITS).contains(&field.len())
                    && field.bytes().all(|byte| byte.is_ascii_digit())
            });
        if valid {
            Ok(Self(text))
        } else {
            Err(Error::new("an xray version is three dot-separated numbers"))
        }
    }
}

impl From<Version> for String {
    fn from(version: Version) -> Self {
        version.0
    }
}

impl std::str::FromStr for Version {
    type Err = Error;

    fn from_str(text: &str) -> Result<Self, Error> {
        text.to_owned().try_into()
    }
}

/// What a job asks the privileged runner to do.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum JobKind {
    RestartXray,
    SwapXray {
        version: Version,
    },
    /// Replaces xray's config with the staged one, then restarts.
    ApplyConfig,
}

impl JobKind {
    /// Whether the job leaves a config or binary behind that a rollback has to restore.
    pub fn changes_files(&self) -> bool {
        !matches!(self, Self::RestartXray)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum JobState {
    Queued,
    Running,
    AwaitingConfirm,
    Confirmed,
    RolledBack,
    Failed,
}

impl JobState {
    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Confirmed | Self::RolledBack | Self::Failed)
    }
}

impl fmt::Display for JobState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Queued => "queued",
            Self::Running => "running",
            Self::AwaitingConfirm => "awaiting-confirm",
            Self::Confirmed => "confirmed",
            Self::RolledBack => "rolled-back",
            Self::Failed => "failed",
        })
    }
}

/// The record in `jobs/<id>.json`. Times are unix seconds; `reason` is a short fixed phrase,
/// never output from xray or contents of its config.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Job {
    pub id: JobId,
    #[serde(flatten)]
    pub kind: JobKind,
    pub state: JobState,
    pub reason: Option<String>,
    pub queued_at: u64,
    pub updated_at: u64,
    /// While awaiting confirm: when the runner gives up and rolls back.
    pub confirm_by: Option<u64>,
}

/// The line in `requests/<id>.req`: a verb, the job id, and for a swap the version.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Request {
    pub id: JobId,
    pub kind: JobKind,
}

impl Request {
    pub fn line(&self) -> String {
        match &self.kind {
            JobKind::RestartXray => format!("restart-xray {}\n", self.id),
            JobKind::ApplyConfig => format!("apply-config {}\n", self.id),
            JobKind::SwapXray { version } => {
                format!("swap-xray {} {}\n", self.id, version.as_str())
            }
        }
    }

    /// Accepts only the fixed verbs, single spaces and a final newline.
    pub fn parse(text: &str) -> Result<Self, Error> {
        let line = text
            .strip_suffix('\n')
            .ok_or_else(|| Error::new("a request is one line"))?;
        let mut words = line.split(' ');
        let (Some(verb), Some(id)) = (words.next(), words.next()) else {
            return Err(Error::new("a request names a verb and a job"));
        };
        let id = JobId::try_from(id.to_owned())?;
        let argument = words.next();
        if words.next().is_some() {
            return Err(Error::new("too many words in the request"));
        }
        let kind = match (verb, argument) {
            ("restart-xray", None) => JobKind::RestartXray,
            ("apply-config", None) => JobKind::ApplyConfig,
            ("swap-xray", Some(version)) => JobKind::SwapXray {
                version: version.parse()?,
            },
            _ => return Err(Error::new("not a verb the runner performs")),
        };
        Ok(Self { id, kind })
    }
}
