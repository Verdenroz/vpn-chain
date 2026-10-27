use std::process::ExitCode;

use relay_jobs::Layout;
use relay_jobs::runner::{Runner, Systemd};

const USAGE: &str = "usage: relay-privileged drain";
const EX_USAGE: u8 = 64;

/// Started as root by relay-privileged.service. It takes no job from its arguments: the only
/// input is the request files in the requests directory, and it refuses all but the fixed verbs.
fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args != ["drain"] {
        eprintln!("{USAGE}");
        return ExitCode::from(EX_USAGE);
    }
    let layout = Layout::from_env();
    match Runner::new(&layout, &Systemd).drain() {
        0 => ExitCode::SUCCESS,
        _ => ExitCode::FAILURE,
    }
}
