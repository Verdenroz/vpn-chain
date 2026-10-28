//! What the live tests share: a dockerised Xray relay and the sing-box clients the apps ship,
//! on the host network. Needs docker; the Xray and sing-box builds come from `fetch-bins.sh`.

mod lab;
mod tunnel;

pub use lab::{
    LOOPBACK_HOST, LOOPBACK6_HOST, Lab, PRIVATE_HOST, PUBLIC_HOST, SING_BOX, SNI, free_port,
    short_id, wait_for_port,
};
pub use tunnel::{Tunnel, echo_server, echo_server_on, roundtrip, roundtrip_to, roundtrip_until};
