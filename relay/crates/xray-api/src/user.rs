use std::fmt;
use std::str::FromStr;

use prost::Message;
use uuid::Uuid;

use crate::Error;
use crate::proto::xray::common::protocol::User;
use crate::proto::xray::common::serial::TypedMessage;
use crate::proto::xray::proxy::vless::Account;

const VISION: &str = "xtls-rprx-vision";
const ACCOUNT_TYPE: &str = "xray.proxy.vless.Account";

/// A VLESS user's UUID. It authenticates the device, so `Debug` and
/// `Display` redact it.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct UserId(Uuid);

impl UserId {
    pub fn new(id: Uuid) -> Self {
        Self(id)
    }

    pub fn expose(&self) -> &Uuid {
        &self.0
    }
}

impl FromStr for UserId {
    type Err = uuid::Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Uuid::parse_str(s).map(Self)
    }
}

impl fmt::Debug for UserId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("UserId(<redacted>)")
    }
}

impl fmt::Display for UserId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("<redacted>")
    }
}

/// A user on a VLESS+REALITY inbound. The email names the user to xray and
/// keys its traffic counters; xray compares it case-insensitively.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VlessUser {
    pub email: String,
    pub id: UserId,
    pub level: u32,
}

impl VlessUser {
    pub(crate) fn into_proto(self) -> User {
        let account = Account {
            id: self.id.0.hyphenated().to_string(),
            flow: VISION.to_owned(),
            ..Account::default()
        };
        User {
            level: self.level,
            email: self.email,
            account: Some(TypedMessage {
                r#type: ACCOUNT_TYPE.to_owned(),
                value: account.encode_to_vec(),
            }),
        }
    }

    pub(crate) fn from_proto(user: User) -> Result<Self, Error> {
        let id = user
            .account
            .filter(|account| account.r#type == ACCOUNT_TYPE)
            .and_then(|account| Account::decode(account.value.as_slice()).ok())
            .and_then(|account| account.id.parse().ok());
        match id {
            Some(id) => Ok(Self {
                email: user.email,
                id,
                level: user.level,
            }),
            None => Err(Error::Account(user.email)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "8f5b1c3e-2d4a-4e6f-9a7b-0c1d2e3f4a5b";

    fn laptop() -> VlessUser {
        VlessUser {
            email: "laptop".to_owned(),
            id: ID.parse().unwrap(),
            level: 0,
        }
    }

    #[test]
    fn debug_and_display_hide_the_uuid() {
        let user = laptop();
        assert!(!format!("{user:?}").contains(ID));
        assert!(!format!("{:?}", user.id).contains(ID));
        assert_eq!(user.id.to_string(), "<redacted>");
    }

    #[test]
    fn proto_user_carries_a_vision_account_typed_for_xray() {
        let user = laptop().into_proto();
        let typed = user.account.unwrap();
        let account = Account::decode(typed.value.as_slice()).unwrap();
        assert_eq!(typed.r#type, "xray.proxy.vless.Account");
        assert_eq!(account.id, ID);
        assert_eq!(account.flow, "xtls-rprx-vision");
        assert_eq!(account.encryption, "");
        assert_eq!(user.email, "laptop");
    }

    #[test]
    fn proto_round_trip_keeps_the_user() {
        assert_eq!(
            VlessUser::from_proto(laptop().into_proto()).unwrap(),
            laptop()
        );
    }

    #[test]
    fn non_vless_account_is_refused_by_email() {
        let mut user = laptop().into_proto();
        user.account.as_mut().unwrap().r#type = "xray.proxy.trojan.Account".to_owned();
        assert!(
            matches!(VlessUser::from_proto(user), Err(Error::Account(email)) if email == "laptop")
        );
    }
}
