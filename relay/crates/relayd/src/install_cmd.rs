use relayd::Error;
use relayd::install::{self, Options};

use crate::args::Args;

pub fn install(args: &[&str]) -> Option<Result<(), Error>> {
    let mut args = Args::parse(
        args,
        &[
            "name",
            "device",
            "sni",
            "port",
            "host",
            "wireguard",
            "entry",
        ],
        &[],
    )?;
    let [] = args.words()?;
    let options = Options {
        name: args.value("name"),
        device: args.value("device"),
        sni: args.value("sni"),
        port: match args.value("port") {
            Some(port) => Some(port.parse().ok()?),
            None => None,
        },
        host: args.value("host"),
        wireguard: args.path("wireguard"),
        entry: args.path("entry"),
    };
    Some(install::run_install(&options))
}
