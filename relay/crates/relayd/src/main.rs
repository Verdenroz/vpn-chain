mod args;
mod device_cmd;
mod egress_cmd;
mod install_cmd;
mod pair_cmd;
mod serve_cmd;
mod target;

use std::path::PathBuf;
use std::process::ExitCode;

use relayd::{
    DEVICES_LOCK, Error, Placement, Relay, Restart, Systemctl, adopt, import, secrets_path,
    write_config,
};

use args::Args;
use target::XRAY_GROUP;

const USAGE: &str = "usage: relayd install [--name <device> | --device <device>] [--sni <host>] [--port <n>] [--host <address>] [--wireguard <wg-quick file>]
       relayd import --config <config.json> [--devices <devices.json>] --out <state.json> [--secrets <secrets.json>] [--lock <file>] [--admin <name>]
       relayd adopt --config <config.json> [--devices <devices.json>] --state <state.json> [--secrets <secrets.json>] [--lock <file>] [--admin <name>] [--xray <path>] [--group <name>] [--restart]
       relayd render --state <state.json> [--secrets <secrets.json>] --out <config.json> [--group <name>] [--swap [--xray <path>]]
       relayd device list --state <state.json> [--secrets <secrets.json>]
       relayd device add <name> --out <identity.json> [--role member|admin] [--allow-restart] <target>
       relayd device rename <old> <new> <target>
       relayd device rekey <name> --out <identity.json> <target>
       relayd device revoke <name> [--force] <target>
       relayd maintenance refill-pool <target>
       relayd keys add <device> --public-key <file> [--role member|admin] --state <state.json> [--secrets <secrets.json>] [--lock <file>]
       relayd serve [--audit <audit.jsonl>] [--jobs <state dir>] <target>
       relayd pair [--device <name>] [--state <state.json>] [--secrets <secrets.json>] [--lock <file>]
       relayd egress (--state <state.json> [--secrets <secrets.json>] | --agent-port <n>) [--resolv <file>] [--print]
target: --state <state.json> --config <config.json> [--secrets <secrets.json>] [--lock <file>] [--xray <path>] [--group <name>] [--spares <n>]";

const EX_USAGE: u8 = 64;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let result = match args.as_slice() {
        ["import", rest @ ..] => run_import(rest),
        ["adopt", rest @ ..] => run_adopt(rest),
        ["render", rest @ ..] => run_render(rest),
        ["device", rest @ ..] => device_cmd::device(rest),
        ["maintenance", rest @ ..] => device_cmd::maintenance(rest),
        ["keys", rest @ ..] => serve_cmd::keys(rest),
        ["serve", rest @ ..] => serve_cmd::serve(rest),
        ["install", rest @ ..] => install_cmd::install(rest),
        ["pair", rest @ ..] => pair_cmd::pair(rest),
        ["egress", rest @ ..] => egress_cmd::egress(rest),
        _ => None,
    };
    match result {
        Some(Ok(())) => ExitCode::SUCCESS,
        Some(Err(error)) => {
            eprintln!("relayd: {error}");
            ExitCode::FAILURE
        }
        None => {
            eprintln!("{USAGE}");
            ExitCode::from(EX_USAGE)
        }
    }
}

fn run_import(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(
        args,
        &["config", "devices", "out", "secrets", "lock", "admin"],
        &[],
    )?;
    let [] = args.words()?;
    let config = args.path("config")?;
    let out = args.path("out")?;
    let devices = args
        .path("devices")
        .unwrap_or_else(|| config.with_file_name("devices.json"));
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&out));
    let lock = args
        .path("lock")
        .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK));
    let admin = args.value("admin");
    Some(import(&config, &devices, &lock).and_then(|relay| {
        let relay = match admin {
            Some(name) => relay.with_admin(&name)?,
            None => relay,
        };
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

fn run_adopt(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(
        args,
        &[
            "config", "devices", "state", "secrets", "lock", "admin", "xray", "group",
        ],
        &["restart"],
    )?;
    let [] = args.words()?;
    let config = args.path("config")?;
    let state = args.path("state")?;
    let devices = args
        .path("devices")
        .unwrap_or_else(|| config.with_file_name("devices.json"));
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&state));
    let lock = args
        .path("lock")
        .unwrap_or_else(|| PathBuf::from(DEVICES_LOCK));
    let xray = args.path("xray").unwrap_or_else(|| PathBuf::from("xray"));
    let group = args.value("group").unwrap_or_else(|| XRAY_GROUP.to_owned());
    let admin = args.value("admin");
    let restart = args.has("restart");
    let placement = Placement {
        group: &group,
        swap: Some(&xray),
    };
    Some(
        adopt(
            &config,
            &devices,
            &state,
            &secrets,
            &lock,
            admin.as_deref(),
            &placement,
        )
        .and_then(|needs_restart| {
            println!("adopted {} into {}", config.display(), state.display());
            match (needs_restart, restart) {
                (false, _) => Ok(()),
                (true, true) => {
                    Systemctl.restart()?;
                    println!("restarted xray; it now runs the rendered config");
                    Ok(())
                }
                (true, false) => {
                    println!(
                        "xray runs the old config until it restarts: run `systemctl restart xray` before changing devices (open connections drop)"
                    );
                    Ok(())
                }
            }
        }),
    )
}

fn run_render(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(
        args,
        &["state", "secrets", "out", "group", "xray"],
        &["swap"],
    )?;
    let [] = args.words()?;
    let state = args.path("state")?;
    let out = args.path("out")?;
    let secrets = args.path("secrets").unwrap_or_else(|| secrets_path(&state));
    let group = args.value("group").unwrap_or_else(|| XRAY_GROUP.to_owned());
    let xray = args.path("xray");
    let swap = args.has("swap");
    if xray.is_some() && !swap {
        return None;
    }
    let xray = xray.unwrap_or_else(|| PathBuf::from("xray"));
    let placement = Placement {
        group: &group,
        swap: swap.then_some(xray.as_path()),
    };
    Some(Relay::load(&state, &secrets).and_then(|relay| write_config(&relay, &out, &placement)))
}
