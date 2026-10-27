use std::collections::HashMap;
use std::path::PathBuf;
use std::process::ExitCode;

use relayd::{DEVICES_LOCK, Error, Placement, Relay, import, secrets_path, write_config};

const USAGE: &str = "usage: relayd import --config <config.json> [--devices <devices.json>] --out <state.json> [--secrets <secrets.json>] [--lock <file>]
       relayd render --state <state.json> [--secrets <secrets.json>] --out <config.json> [--group <name>] [--swap [--xray <path>]]";

const EX_USAGE: u8 = 64;
const XRAY_GROUP: &str = "xray";
const SWAP: &str = "--swap";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.split_first() {
        Some((command, rest)) if command == "import" => {
            flags(rest, &["config", "devices", "out", "secrets", "lock"]).map(run_import)
        }
        Some((command, rest)) if command == "render" => {
            let swap = rest.iter().any(|arg| arg == SWAP);
            let rest: Vec<String> = rest.iter().filter(|arg| *arg != SWAP).cloned().collect();
            flags(&rest, &["state", "secrets", "out", "group", "xray"])
                .map(|flags| run_render(flags, swap))
        }
        _ => None,
    };
    match result {
        Some(Some(Ok(()))) => ExitCode::SUCCESS,
        Some(Some(Err(error))) => {
            eprintln!("relayd: {error}");
            ExitCode::FAILURE
        }
        _ => {
            eprintln!("{USAGE}");
            ExitCode::from(EX_USAGE)
        }
    }
}

/// `--name value` pairs, each named in `allowed` and given once.
fn flags(args: &[String], allowed: &[&str]) -> Option<HashMap<String, PathBuf>> {
    let mut flags = HashMap::new();
    for pair in args.chunks(2) {
        let [flag, value] = pair else { return None };
        let name = flag
            .strip_prefix("--")
            .filter(|name| allowed.contains(name))?;
        if flags
            .insert(name.to_owned(), PathBuf::from(value))
            .is_some()
        {
            return None;
        }
    }
    Some(flags)
}

fn run_import(mut flags: HashMap<String, PathBuf>) -> Option<Result<(), Error>> {
    let config = flags.remove("config")?;
    let out = flags.remove("out")?;
    let devices = flags
        .remove("devices")
        .unwrap_or_else(|| config.with_file_name("devices.json"));
    let secrets = flags
        .remove("secrets")
        .unwrap_or_else(|| secrets_path(&out));
    let lock = flags
        .remove("lock")
        .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK));
    Some(import(&config, &devices, &lock).and_then(|relay| {
        relay.create(&out, &secrets)?;
        let names: Vec<&str> = relay
            .state
            .devices
            .iter()
            .map(|d| d.name.as_str())
            .collect();
        println!(
            "imported {} into {}, keys into {}",
            names.join(", "),
            out.display(),
            secrets.display()
        );
        Ok(())
    }))
}

fn run_render(mut flags: HashMap<String, PathBuf>, swap: bool) -> Option<Result<(), Error>> {
    let state = flags.remove("state")?;
    let out = flags.remove("out")?;
    let secrets = flags
        .remove("secrets")
        .unwrap_or_else(|| secrets_path(&state));
    let group = flags
        .remove("group")
        .unwrap_or_else(|| PathBuf::from(XRAY_GROUP));
    let xray = match (swap, flags.remove("xray")) {
        (false, Some(_)) => return None,
        (_, xray) => xray.unwrap_or_else(|| PathBuf::from("xray")),
    };
    let placement = Placement {
        group: group.to_str()?,
        swap: swap.then_some(xray.as_path()),
    };
    Some(Relay::load(&state, &secrets).and_then(|relay| write_config(&relay, &out, &placement)))
}
