use std::fmt::Write as _;
use std::io::IsTerminal;
use std::net::Ipv6Addr;
use std::path::Path;
use std::time::Duration;

use qrcode::{Color, EcLevel, QrCode};
use serde::{Deserialize, Serialize};

use crate::clock::seconds;
use crate::{Enrollments, Error, Relay, Role, Secret, TOKEN_TTL, store};

const LINK_VERSION: u32 = 1;
const PUBLIC_SCHEMA: u32 = 1;
const LOCK_WAIT: Duration = Duration::from_secs(30);
const QUIET_ZONE: usize = 2;

/// What a pairing link carries that is not a secret and that the state does not hold: where the
/// relay is reached, and the REALITY public key that goes with its private one.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Public {
    v: u32,
    pub host: String,
    pub public_key: String,
}

impl Public {
    pub fn new(host: &str, public_key: &str) -> Self {
        Self {
            v: PUBLIC_SCHEMA,
            host: host.to_owned(),
            public_key: public_key.to_owned(),
        }
    }

    /// The file that belongs to the state file at `state`.
    pub fn beside(state: &Path) -> std::path::PathBuf {
        state.with_file_name("public.json")
    }

    pub fn create(&self, path: &Path) -> Result<(), Error> {
        store::create(path, &store::to_json(self), 0o600)
    }

    pub fn read(path: &Path) -> Result<Self, Error> {
        let public: Self = store::read_json(path)?;
        if public.v == PUBLIC_SCHEMA {
            Ok(public)
        } else {
            Err(Error::at(path, "written by another schema version"))
        }
    }
}

/// A new pairing link for `device`, or for the first admin when none is named. The token in it
/// works once, for [`TOKEN_TTL`] seconds, and only its hash is stored. The link is a secret.
pub fn issue(
    state: &Path,
    secrets: &Path,
    lock: &Path,
    device: Option<&str>,
) -> Result<Secret, Error> {
    let relay = Relay::load(state, secrets)?;
    let device = match device {
        Some(name) => relay.state.devices.iter().find(|held| held.name == name),
        None => relay
            .state
            .devices
            .iter()
            .find(|held| held.role == Role::Admin),
    }
    .ok_or_else(|| Error::missing("no such device; name one with --device"))?;
    let public = Public::read(&Public::beside(state))?;
    let now = seconds();
    let token =
        Enrollments::beside(state).issue(lock, LOCK_WAIT, &device.name, device.role, now)?;
    let inbound = &relay.state.inbound;
    let host = match public.host.parse::<Ipv6Addr>() {
        Ok(_) => format!("[{}]", public.host),
        Err(_) => public.host.clone(),
    };
    let pairs = [
        ("v", LINK_VERSION.to_string()),
        ("h", host),
        ("p", inbound.port.to_string()),
        ("sni", inbound.server_names[0].clone()),
        ("pbk", public.public_key),
        ("sid", device.short_id.expose().to_owned()),
        ("id", device.uuid.expose().to_owned()),
        ("n", device.name.clone()),
        ("t", token.expose().to_owned()),
        ("e", (now + TOKEN_TTL).to_string()),
    ];
    let query: Vec<String> = pairs
        .iter()
        .map(|(name, value)| format!("{name}={}", encode(value)))
        .collect();
    Ok(Secret::new(format!("vpnchain://pair?{}", query.join("&"))))
}

/// The link as a QR code and as text, for the terminal. The link is a credential, so this is
/// the only place it is written out.
pub fn show(link: &Secret) -> String {
    let code = QrCode::with_error_correction_level(link.expose(), EcLevel::L)
        .expect("a pairing link fits in a QR code");
    let mut text = String::new();
    writeln!(
        text,
        "Scan this with the vpn-chain app, or paste the link below it. It works once and for {} minutes.\n",
        TOKEN_TTL / 60
    )
    .expect("writing to a string cannot fail");
    text.push_str(&render(&code, std::io::stdout().is_terminal()));
    writeln!(text, "\n{}", link.expose()).expect("writing to a string cannot fail");
    text
}

/// Two module rows per text row. On a terminal the code is black on white, whatever the
/// theme, because scanners read dark modules on a light ground.
fn render(code: &QrCode, color: bool) -> String {
    let width = code.width();
    let dark = |x: usize, y: usize| {
        x >= QUIET_ZONE
            && y >= QUIET_ZONE
            && x < width + QUIET_ZONE
            && y < width + QUIET_ZONE
            && code[(x - QUIET_ZONE, y - QUIET_ZONE)] == Color::Dark
    };
    let side = width + 2 * QUIET_ZONE;
    let (on, off) = if color {
        ("\x1b[30;47m", "\x1b[0m")
    } else {
        ("", "")
    };
    (0..side)
        .step_by(2)
        .map(|y| {
            let row: String = (0..side)
                .map(|x| match (dark(x, y), dark(x, y + 1)) {
                    (true, true) => '█',
                    (true, false) => '▀',
                    (false, true) => '▄',
                    (false, false) => ' ',
                })
                .collect();
            format!("{on}{row}{off}\n")
        })
        .collect()
}

/// Percent-encodes everything but the unreserved characters.
fn encode(value: &str) -> String {
    value
        .bytes()
        .map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                char::from(byte).to_string()
            }
            _ => format!("%{byte:02X}"),
        })
        .collect()
}
