//! The questions `relayd install` asks on a terminal.
//!
//! stdin is the `curl | bash` pipe, so the answers come from `/dev/tty`. A question a flag
//! already answered is not asked, and with no terminal nothing is.

use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use std::process::{Command, Stdio};

use super::entry;
use super::wireguard;
use super::{FIRST_DEVICE, Options};
use crate::{Error, Exit, Secret, valid_name};

/// Which questions are still open for this run.
#[derive(Clone, Copy, Default)]
pub struct Needs {
    pub name: bool,
    pub entry: bool,
    pub exit: bool,
}

/// What the questions and the flags settled.
#[derive(Default)]
pub struct Asked {
    pub name: Option<String>,
    pub entry: Option<Secret>,
    pub exit: Option<(Exit, Secret)>,
}

pub struct Terminal<R, W> {
    input: R,
    output: W,
    echo: Option<File>,
}

impl Terminal<BufReader<File>, File> {
    pub fn open() -> Option<Self> {
        let tty = OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/tty")
            .ok()?;
        Some(Self {
            input: BufReader::new(tty.try_clone().ok()?),
            output: tty.try_clone().ok()?,
            echo: Some(tty),
        })
    }
}

/// Reads `--entry` and asks what is still open. The terminal is opened only when a question
/// needs it.
pub fn ask(options: &Options, needs: Needs) -> Result<Asked, Error> {
    let entry = match &options.entry {
        Some(path) => Some(read_entry(path)?),
        None => None,
    };
    let open = Needs {
        entry: needs.entry && entry.is_none(),
        ..needs
    };
    let terminal = if open.name || open.entry || open.exit {
        Terminal::open()
    } else {
        None
    };
    let mut asked = match terminal {
        Some(mut terminal) => terminal.ask(open)?,
        None => Asked::default(),
    };
    asked.entry = entry.or(asked.entry);
    Ok(asked)
}

fn read_entry(path: &Path) -> Result<Secret, Error> {
    let text = fs::read_to_string(path).map_err(|error| Error::at(path, error))?;
    entry::validate(&text).map_err(|error| Error::at(path, error))?;
    Ok(Secret::new(text))
}

impl<R: BufRead, W: Write> Terminal<R, W> {
    #[cfg(test)]
    pub fn new(input: R, output: W) -> Self {
        Self {
            input,
            output,
            echo: None,
        }
    }

    pub fn ask(&mut self, needs: Needs) -> Result<Asked, Error> {
        let mut asked = Asked::default();
        if needs.name {
            asked.name = Some(self.name()?);
        }
        if needs.entry {
            let intro = "WireGuard entry hop for that device (optional).\n\
                The entry hop is the VPN between the device and this relay.\n\
                Paste the .conf from your VPN provider, then an empty line. Press Enter to skip.";
            asked.entry = self
                .conf(intro, entry::validate)?
                .map(|(text, ())| Secret::new(text));
        }
        if needs.exit {
            let intro = "WireGuard exit for the relay (optional).\n\
                The exit is the VPN between this relay and the internet.\n\
                Paste the .conf from your VPN provider, then an empty line. Press Enter to skip.";
            asked.exit = self.conf(intro, wireguard::parse)?.map(|(_, exit)| exit);
        }
        Ok(asked)
    }

    fn name(&mut self) -> Result<String, Error> {
        loop {
            self.write(&format!("Name for the first device [{FIRST_DEVICE}]: "))?;
            let Some(line) = self.line()? else {
                return Ok(FIRST_DEVICE.to_owned());
            };
            let name = line.trim();
            if name.is_empty() {
                return Ok(FIRST_DEVICE.to_owned());
            }
            if valid_name(name) {
                return Ok(name.to_owned());
            }
            self.write(
                "Use up to 32 lowercase letters, digits, dots, dashes or underscores, starting with a letter or digit.\n",
            )?;
        }
    }

    /// Asks for a pasted file until `check` accepts it or the person skips. A blank line ends
    /// the paste once a `[Peer]` is in, and two in a row always do, because provider files
    /// leave a blank line between sections.
    fn conf<T>(
        &mut self,
        intro: &str,
        check: impl Fn(&str) -> Result<T, Error>,
    ) -> Result<Option<(String, T)>, Error> {
        let mut prompt = format!("{intro}\n");
        loop {
            let Some(text) = self.paste(&prompt)? else {
                return Ok(None);
            };
            match check(&text) {
                Ok(checked) => return Ok(Some((text, checked))),
                Err(error) => {
                    prompt = format!("{error}\nPaste it again, or press Enter to skip.\n");
                }
            }
        }
    }

    /// Echo goes off before the prompt shows, so nothing typed after it can slip onto the screen.
    fn paste(&mut self, prompt: &str) -> Result<Option<String>, Error> {
        self.hide_echo(true);
        let pasted = self.write(prompt).and_then(|()| self.lines());
        self.hide_echo(false);
        let text = pasted?;
        self.write("\n")?;
        Ok((!text.trim().is_empty()).then_some(text))
    }

    fn lines(&mut self) -> Result<String, Error> {
        let mut text = String::new();
        let mut blanks = 0;
        while let Some(line) = self.line()? {
            if !line.trim().is_empty() {
                blanks = 0;
                text.push_str(line.trim_end());
                text.push('\n');
                continue;
            }
            blanks += 1;
            let has_peer = text
                .lines()
                .any(|held| held.trim().eq_ignore_ascii_case("[peer]"));
            if text.is_empty() || has_peer || blanks == 2 {
                break;
            }
            text.push('\n');
        }
        Ok(text)
    }

    fn line(&mut self) -> Result<Option<String>, Error> {
        let mut line = String::new();
        match self.input.read_line(&mut line) {
            Ok(0) => Ok(None),
            Ok(_) => Ok(Some(line)),
            Err(error) => Err(Error::new(format!("terminal: {error}"))),
        }
    }

    fn write(&mut self, text: &str) -> Result<(), Error> {
        self.output
            .write_all(text.as_bytes())
            .and_then(|()| self.output.flush())
            .map_err(|error| Error::new(format!("terminal: {error}")))
    }

    /// Best effort: a terminal `stty` cannot change just shows the paste.
    fn hide_echo(&mut self, hide: bool) {
        let Some(tty) = self.echo.as_ref().and_then(|tty| tty.try_clone().ok()) else {
            return;
        };
        let mode = if hide { "-echo" } else { "echo" };
        let _ = Command::new("stty")
            .arg(mode)
            .stdin(Stdio::from(tty))
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    const ENTRY: &str = "[Interface]\nPrivateKey = kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=\nAddress = 10.8.0.2/32\nDNS = 10.8.0.1\n\n[Peer]\nPublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 203.0.113.9:51820\n";
    const ALL: Needs = Needs {
        name: true,
        entry: true,
        exit: true,
    };

    fn run(input: &str, needs: Needs) -> (Asked, String) {
        let mut shown = Vec::new();
        let asked = Terminal::new(Cursor::new(input.to_owned()), &mut shown)
            .ask(needs)
            .unwrap();
        (asked, String::from_utf8(shown).unwrap())
    }

    fn only_entry() -> Needs {
        Needs {
            entry: true,
            ..Needs::default()
        }
    }

    #[test]
    fn a_name_and_a_pasted_conf_are_taken() {
        let (asked, _) = run(&format!("laptop\n{ENTRY}\n\n"), ALL);

        assert_eq!(asked.name.as_deref(), Some("laptop"));
        assert_eq!(asked.entry.unwrap().expose(), ENTRY);
        assert!(asked.exit.is_none());
    }

    #[test]
    fn enter_alone_takes_the_default_name_and_skips_the_confs() {
        let (asked, shown) = run("\n\n\n", ALL);

        assert_eq!(asked.name.as_deref(), Some("admin"));
        assert!(asked.entry.is_none() && asked.exit.is_none());
        assert!(shown.contains("[admin]"), "{shown}");
    }

    #[test]
    fn a_bad_name_is_asked_again() {
        let (asked, shown) = run(
            "Bad Name\nlaptop\n",
            Needs {
                name: true,
                ..Needs::default()
            },
        );

        assert_eq!(asked.name.as_deref(), Some("laptop"));
        assert_eq!(shown.matches("Name for the first device").count(), 2);
    }

    #[test]
    fn a_bad_conf_says_why_and_is_asked_again() {
        let split = ENTRY.replace("0.0.0.0/0, ::/0", "10.0.0.0/8");
        let (asked, shown) = run(&format!("{split}\n{ENTRY}\n"), only_entry());

        assert!(asked.entry.is_some());
        assert!(shown.contains("Only full-tunnel configs work."), "{shown}");
        assert!(shown.contains("Paste it again"), "{shown}");
    }

    #[test]
    fn a_blank_line_inside_the_paste_does_not_end_it() {
        let (asked, _) = run(&format!("{ENTRY}\n"), only_entry());

        assert!(asked.entry.unwrap().expose().contains("[Peer]"));
    }

    #[test]
    fn the_prompt_and_the_reasons_never_show_the_conf() {
        let bad = ENTRY.replace("Endpoint = 203.0.113.9:51820", "Endpoint = vpn.test:1");
        let (_, shown) = run(&format!("{bad}\n\n"), only_entry());

        assert!(
            !shown.contains("kPwG0r8") && !shown.contains("pEeRpUb"),
            "{shown}"
        );
    }

    #[test]
    fn the_exit_conf_is_read_by_the_exit_rules() {
        let (asked, shown) = run(
            &format!("not a conf\n\n\n{ENTRY}\n"),
            Needs {
                exit: true,
                ..Needs::default()
            },
        );

        let (exit, key) = asked.exit.unwrap();
        assert!(matches!(exit, Exit::WireGuard { .. }));
        assert_eq!(key.expose(), "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM=");
        assert!(
            shown.contains("between this relay and the internet"),
            "{shown}"
        );
    }

    #[test]
    fn a_closed_terminal_takes_the_defaults() {
        let (asked, _) = run("", ALL);

        assert_eq!(asked.name.as_deref(), Some("admin"));
        assert!(asked.entry.is_none());
    }

    #[test]
    fn a_flag_answers_without_a_terminal() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("entry.conf");
        fs::write(&path, ENTRY).unwrap();
        let options = Options {
            name: Some("laptop".to_owned()),
            entry: Some(path),
            ..Options::default()
        };

        let asked = ask(&options, Needs::default()).unwrap();

        assert_eq!(asked.entry.unwrap().expose(), ENTRY);
    }

    #[test]
    fn a_bad_entry_flag_names_the_file_and_the_field_only() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("entry.conf");
        fs::write(&path, ENTRY.replace("Address", "Adres")).unwrap();
        let options = Options {
            entry: Some(path),
            ..Options::default()
        };

        let error = ask(&options, Needs::default()).err().unwrap().to_string();

        assert!(error.contains("Missing Address."), "{error}");
        assert!(!error.contains("kPwG0r8"), "{error}");
    }
}
