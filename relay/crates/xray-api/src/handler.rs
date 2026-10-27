use prost::{Message, Name};

use crate::proto::xray::app::proxyman::command::{
    AddUserOperation, AlterInboundRequest, GetInboundUserRequest, RemoveUserOperation,
};
use crate::proto::xray::common::serial::TypedMessage;
use crate::{Error, VlessUser, XrayApi};

impl XrayApi {
    /// Adds `user` to the inbound tagged `inbound`. Takes effect for new
    /// connections at once; xray keeps it only until it restarts.
    pub async fn add_user(&self, inbound: &str, user: VlessUser) -> Result<(), Error> {
        let email = user.email.clone();
        let operation = AddUserOperation {
            user: Some(user.into_proto()),
        };
        self.alter_inbound(inbound, typed(&operation))
            .await
            .map_err(|error| match error {
                Error::Rpc { message, .. } if message.ends_with("already exists.") => {
                    Error::UserExists(email)
                }
                error => error,
            })
    }

    /// Removes the user named `email` from the inbound tagged `inbound`.
    pub async fn remove_user(&self, inbound: &str, email: &str) -> Result<(), Error> {
        let operation = RemoveUserOperation {
            email: email.to_owned(),
        };
        self.alter_inbound(inbound, typed(&operation))
            .await
            .map_err(|error| match error {
                Error::Rpc { message, .. } if message.ends_with("not found.") => {
                    Error::UserNotFound(email.to_owned())
                }
                error => error,
            })
    }

    /// Every user xray currently accepts on the inbound tagged `inbound`.
    pub async fn users(&self, inbound: &str) -> Result<Vec<VlessUser>, Error> {
        let request = GetInboundUserRequest {
            tag: inbound.to_owned(),
            email: String::new(),
        };
        let response = self
            .within(self.handler.clone().get_inbound_users(request))
            .await?;
        response
            .users
            .into_iter()
            .map(VlessUser::from_proto)
            .collect()
    }

    async fn alter_inbound(&self, inbound: &str, operation: TypedMessage) -> Result<(), Error> {
        let request = AlterInboundRequest {
            tag: inbound.to_owned(),
            operation: Some(operation),
        };
        self.within(self.handler.clone().alter_inbound(request))
            .await
            .map(drop)
    }
}

fn typed<M: Message + Name>(message: &M) -> TypedMessage {
    TypedMessage {
        r#type: M::full_name(),
        value: message.encode_to_vec(),
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tonic::{Code, Status};

    use super::*;
    use crate::fake::{FakeXray, Reply};
    use crate::proto::xray::common::protocol::User;

    fn phone() -> VlessUser {
        VlessUser {
            email: "phone".to_owned(),
            id: "0f7e4a2b-9c1d-4b3e-8a5f-6d7c8b9a0e1f".parse().unwrap(),
            level: 0,
        }
    }

    #[tokio::test]
    async fn add_user_sends_an_add_user_operation_for_the_tag() {
        let xray = FakeXray::start(Reply::Ok).await;
        xray.api()
            .add_user("vless-reality-443", phone())
            .await
            .unwrap();

        let request = xray.last_alter().unwrap();
        let operation = request.operation.unwrap();
        let added = AddUserOperation::decode(operation.value.as_slice()).unwrap();
        assert_eq!(request.tag, "vless-reality-443");
        assert_eq!(
            operation.r#type,
            "xray.app.proxyman.command.AddUserOperation"
        );
        assert_eq!(VlessUser::from_proto(added.user.unwrap()).unwrap(), phone());
    }

    #[tokio::test]
    async fn remove_user_sends_a_remove_user_operation_by_email() {
        let xray = FakeXray::start(Reply::Ok).await;
        xray.api()
            .remove_user("vless-reality-443", "phone")
            .await
            .unwrap();

        let operation = xray.last_alter().unwrap().operation.unwrap();
        let removed = RemoveUserOperation::decode(operation.value.as_slice()).unwrap();
        assert_eq!(
            operation.r#type,
            "xray.app.proxyman.command.RemoveUserOperation"
        );
        assert_eq!(removed.email, "phone");
    }

    #[tokio::test]
    async fn duplicate_add_is_user_exists() {
        let message =
            "app/proxyman/command: failed to add user > proxy/vless: User phone already exists.";
        let xray = FakeXray::start(Reply::Fail(Status::unknown(message))).await;
        let error = xray
            .api()
            .add_user("vless-reality-443", phone())
            .await
            .unwrap_err();
        assert!(matches!(error, Error::UserExists(email) if email == "phone"));
    }

    #[tokio::test]
    async fn removing_a_missing_user_is_user_not_found() {
        let message = "proxy/vless: User phone not found.";
        let xray = FakeXray::start(Reply::Fail(Status::unknown(message))).await;
        let error = xray
            .api()
            .remove_user("vless-reality-443", "phone")
            .await
            .unwrap_err();
        assert!(matches!(error, Error::UserNotFound(email) if email == "phone"));
    }

    #[tokio::test]
    async fn unknown_inbound_is_an_rpc_error() {
        let message = "app/proxyman/command: failed to get handler: nope";
        let xray = FakeXray::start(Reply::Fail(Status::unknown(message))).await;
        let error = xray.api().add_user("nope", phone()).await.unwrap_err();
        assert!(matches!(
            error,
            Error::Rpc {
                code: Code::Unknown,
                ..
            }
        ));
    }

    #[tokio::test]
    async fn users_lists_the_inbound() {
        let xray = FakeXray::start(Reply::Ok).await;
        xray.set_users(vec![phone().into_proto()]);
        assert_eq!(
            xray.api().users("vless-reality-443").await.unwrap(),
            vec![phone()]
        );
    }

    #[tokio::test]
    async fn users_refuses_an_unreadable_account() {
        let xray = FakeXray::start(Reply::Ok).await;
        xray.set_users(vec![User {
            email: "broken".to_owned(),
            ..User::default()
        }]);
        let error = xray.api().users("vless-reality-443").await.unwrap_err();
        assert!(matches!(error, Error::Account(email) if email == "broken"));
    }

    #[tokio::test]
    async fn slow_xray_times_out() {
        let xray = FakeXray::start(Reply::Stall).await;
        let error = xray
            .api()
            .remove_user("vless-reality-443", "phone")
            .await
            .unwrap_err();
        assert!(matches!(error, Error::Timeout(_)));
    }

    #[tokio::test]
    async fn closed_port_is_unavailable() {
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap();
        let api = XrayApi::new(port, Duration::from_secs(2));
        let error = api.users("vless-reality-443").await.unwrap_err();
        assert!(matches!(error, Error::Unavailable(_)), "{error:?}");
    }
}
