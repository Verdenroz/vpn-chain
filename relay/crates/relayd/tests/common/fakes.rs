#![allow(dead_code)]

use std::collections::{BTreeMap, VecDeque};
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};

use relayd::{Error, Live, Restart};
use xray_api::{Traffic, VlessUser};

type Outcome = Result<(), xray_api::Error>;

/// An xray API that records its calls and answers from scripts, `Ok` once a script runs dry.
#[derive(Default)]
pub struct FakeLive {
    pub calls: Mutex<Vec<String>>,
    pub traffic: Mutex<BTreeMap<String, Traffic>>,
    pub drain_fails: Mutex<bool>,
    pub adds: Mutex<VecDeque<Outcome>>,
    pub removes: Mutex<VecDeque<Outcome>>,
    pub added_ids: Mutex<Vec<(String, String)>>,
}

impl FakeLive {
    pub fn calls(&self) -> Vec<String> {
        self.calls.lock().unwrap().clone()
    }

    pub fn script_adds(&self, outcomes: impl IntoIterator<Item = Outcome>) {
        self.adds.lock().unwrap().extend(outcomes);
    }

    pub fn script_removes(&self, outcomes: impl IntoIterator<Item = Outcome>) {
        self.removes.lock().unwrap().extend(outcomes);
    }
}

impl Live for FakeLive {
    fn add_user(&self, user: VlessUser) -> Outcome {
        self.calls
            .lock()
            .unwrap()
            .push(format!("add {}", user.email));
        self.added_ids
            .lock()
            .unwrap()
            .push((user.email, user.id.expose().to_string()));
        self.adds.lock().unwrap().pop_front().unwrap_or(Ok(()))
    }

    fn remove_user(&self, email: &str) -> Outcome {
        self.calls.lock().unwrap().push(format!("remove {email}"));
        self.removes.lock().unwrap().pop_front().unwrap_or(Ok(()))
    }

    fn drain_traffic(&self) -> Result<BTreeMap<String, Traffic>, xray_api::Error> {
        self.calls.lock().unwrap().push("drain".to_owned());
        if *self.drain_fails.lock().unwrap() {
            return Err(xray_api::Error::Unavailable("down".to_owned()));
        }
        Ok(std::mem::take(&mut *self.traffic.lock().unwrap()))
    }
}

#[derive(Default)]
pub struct FakeRestart {
    pub restarts: AtomicUsize,
    pub fails: Mutex<bool>,
}

impl FakeRestart {
    pub fn count(&self) -> usize {
        self.restarts.load(Ordering::SeqCst)
    }
}

impl Restart for FakeRestart {
    fn restart(&self) -> Result<(), Error> {
        self.restarts.fetch_add(1, Ordering::SeqCst);
        if *self.fails.lock().unwrap() {
            return Err(Error::new("systemctl restart xray failed"));
        }
        Ok(())
    }
}
