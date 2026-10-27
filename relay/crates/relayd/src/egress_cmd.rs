use std::path::{Path, PathBuf};

use relayd::{Error, Relay, egress, secrets_path};

use crate::args::Args;

enum AgentPort {
    Given(u16),
    FromState { state: PathBuf, secrets: PathBuf },
}

pub fn egress(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(
        args,
        &["state", "secrets", "agent-port", "resolv"],
        &["print"],
    )?;
    let [] = args.words()?;
    let port = match (args.path("state"), args.value("agent-port")) {
        (Some(state), None) => AgentPort::FromState {
            secrets: args.path("secrets").unwrap_or_else(|| secrets_path(&state)),
            state,
        },
        (None, Some(port)) => AgentPort::Given(port.parse().ok()?),
        _ => return None,
    };
    let resolv = args
        .path("resolv")
        .unwrap_or_else(|| PathBuf::from(egress::RESOLV_CONF));
    Some(run(&port, &resolv, args.has("print")))
}

fn run(port: &AgentPort, resolv: &Path, print: bool) -> Result<(), Error> {
    let port = match port {
        AgentPort::Given(port) => *port,
        AgentPort::FromState { state, secrets } => Relay::load(state, secrets)?.state.agent.port,
    };
    let rules = egress::rules(port, resolv)?;
    if print {
        print!("{rules}");
        Ok(())
    } else {
        egress::apply(&rules)
    }
}
