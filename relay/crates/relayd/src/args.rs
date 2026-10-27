use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

/// A command line of bare words, `--name value` pairs and `--name` switches, each flag named in
/// the lists the caller allows and given once.
pub struct Args {
    words: Vec<String>,
    values: HashMap<String, String>,
    switches: HashSet<String>,
}

impl Args {
    pub fn parse(args: &[&str], valued: &[&str], switches: &[&str]) -> Option<Self> {
        let mut parsed = Self {
            words: Vec::new(),
            values: HashMap::new(),
            switches: HashSet::new(),
        };
        let mut rest = args.iter();
        while let Some(arg) = rest.next() {
            let Some(name) = arg.strip_prefix("--") else {
                parsed.words.push((*arg).to_owned());
                continue;
            };
            let fresh = if switches.contains(&name) {
                parsed.switches.insert(name.to_owned())
            } else if valued.contains(&name) {
                let value = rest.next()?;
                parsed
                    .values
                    .insert(name.to_owned(), (*value).to_owned())
                    .is_none()
            } else {
                false
            };
            if !fresh {
                return None;
            }
        }
        Some(parsed)
    }

    /// The bare words, if there are exactly `N`.
    pub fn words<const N: usize>(&self) -> Option<[&str; N]> {
        let words: Vec<&str> = self.words.iter().map(String::as_str).collect();
        words.try_into().ok()
    }

    pub fn value(&mut self, name: &str) -> Option<String> {
        self.values.remove(name)
    }

    pub fn path(&mut self, name: &str) -> Option<PathBuf> {
        self.value(name).map(PathBuf::from)
    }

    pub fn has(&self, name: &str) -> bool {
        self.switches.contains(name)
    }
}
