//! The agent: the HTTP API the app reaches through its own tunnel to manage the relay.
//!
//! It listens on loopback only. xray routes the reserved name [`crate::AGENT_HOST`] to that port
//! for any client that authenticated to REALITY, and every other loopback port stays blocked, so
//! the API has no public address to scan. Each request must also carry a signature from a
//! management key relayd holds, which is what tells an admin from a member.

mod auth;

pub use auth::{CONTEXT, Principal, Reject, Signed, Verifier, WINDOW, Why, message};
