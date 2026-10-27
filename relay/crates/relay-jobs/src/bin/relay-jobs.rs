use std::process::ExitCode;

use relay_jobs::{Error, JobId, JobKind, Jobs, Layout};

const USAGE: &str = "usage: relay-jobs submit restart-xray | apply-config | swap-xray <version>
       relay-jobs status <job id>
       relay-jobs confirm <job id>";

const EX_USAGE: u8 = 64;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let jobs = Jobs::new(Layout::from_env());
    let result = match args[..] {
        ["submit", "restart-xray"] => submit(&jobs, JobKind::RestartXray),
        ["submit", "apply-config"] => submit(&jobs, JobKind::ApplyConfig),
        ["submit", "swap-xray", version] => version
            .parse()
            .and_then(|version| submit(&jobs, JobKind::SwapXray { version })),
        ["status", id] => id.to_owned().try_into().and_then(|id| status(&jobs, &id)),
        ["confirm", id] => id.to_owned().try_into().and_then(|id| jobs.confirm(&id)),
        _ => {
            eprintln!("{USAGE}");
            return ExitCode::from(EX_USAGE);
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("relay-jobs: {error}");
            ExitCode::FAILURE
        }
    }
}

fn submit(jobs: &Jobs, kind: JobKind) -> Result<(), Error> {
    println!("{}", jobs.submit(kind)?);
    Ok(())
}

fn status(jobs: &Jobs, id: &JobId) -> Result<(), Error> {
    let job = jobs.status(id)?;
    match job.reason {
        Some(reason) => println!("{} {reason}", job.state),
        None => println!("{}", job.state),
    }
    Ok(())
}
