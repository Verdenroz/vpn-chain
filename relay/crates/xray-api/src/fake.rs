use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::net::TcpListener;
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::Server;
use tonic::{Request, Response, Status};

use crate::XrayApi;
use crate::proto::xray::app::proxyman::command::handler_service_server::{
    HandlerService, HandlerServiceServer,
};
use crate::proto::xray::app::proxyman::command::{
    AlterInboundRequest, AlterInboundResponse, GetInboundUserRequest, GetInboundUserResponse,
};
use crate::proto::xray::app::stats::command::stats_service_server::{
    StatsService, StatsServiceServer,
};
use crate::proto::xray::app::stats::command::{QueryStatsRequest, QueryStatsResponse, Stat};
use crate::proto::xray::common::protocol::User;

pub(crate) const TIMEOUT: Duration = Duration::from_millis(300);

#[derive(Clone)]
pub(crate) enum Reply {
    Ok,
    Fail(Status),
    Stall,
}

#[derive(Default)]
struct Seen {
    alters: Vec<AlterInboundRequest>,
    queries: Vec<QueryStatsRequest>,
    users: Vec<User>,
    stats: Vec<Stat>,
}

/// An in-process stand-in for xray's API inbound.
#[derive(Clone)]
pub(crate) struct FakeXray {
    addr: SocketAddr,
    reply: Reply,
    seen: Arc<Mutex<Seen>>,
}

impl FakeXray {
    pub(crate) async fn start(reply: Reply) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let fake = Self {
            addr: listener.local_addr().unwrap(),
            reply,
            seen: Arc::default(),
        };
        let server = Server::builder()
            .add_service(HandlerServiceServer::new(fake.clone()))
            .add_service(StatsServiceServer::new(fake.clone()))
            .serve_with_incoming(TcpListenerStream::new(listener));
        tokio::spawn(server);
        fake
    }

    pub(crate) fn api(&self) -> XrayApi {
        XrayApi::new(self.addr, TIMEOUT)
    }

    pub(crate) fn last_alter(&self) -> Option<AlterInboundRequest> {
        self.seen.lock().unwrap().alters.last().cloned()
    }

    pub(crate) fn last_query(&self) -> Option<QueryStatsRequest> {
        self.seen.lock().unwrap().queries.last().cloned()
    }

    pub(crate) fn set_users(&self, users: Vec<User>) {
        self.seen.lock().unwrap().users = users;
    }

    pub(crate) fn set_stats(&self, stats: Vec<Stat>) {
        self.seen.lock().unwrap().stats = stats;
    }

    async fn reply<T>(&self, ok: T) -> Result<Response<T>, Status> {
        match &self.reply {
            Reply::Ok => Ok(Response::new(ok)),
            Reply::Fail(status) => Err(status.clone()),
            Reply::Stall => {
                tokio::time::sleep(TIMEOUT * 10).await;
                Ok(Response::new(ok))
            }
        }
    }
}

#[tonic::async_trait]
impl HandlerService for FakeXray {
    async fn alter_inbound(
        &self,
        request: Request<AlterInboundRequest>,
    ) -> Result<Response<AlterInboundResponse>, Status> {
        self.seen.lock().unwrap().alters.push(request.into_inner());
        self.reply(AlterInboundResponse {}).await
    }

    async fn get_inbound_users(
        &self,
        _: Request<GetInboundUserRequest>,
    ) -> Result<Response<GetInboundUserResponse>, Status> {
        let users = self.seen.lock().unwrap().users.clone();
        self.reply(GetInboundUserResponse { users }).await
    }
}

#[tonic::async_trait]
impl StatsService for FakeXray {
    async fn query_stats(
        &self,
        request: Request<QueryStatsRequest>,
    ) -> Result<Response<QueryStatsResponse>, Status> {
        let stat = {
            let mut seen = self.seen.lock().unwrap();
            seen.queries.push(request.into_inner());
            seen.stats.clone()
        };
        self.reply(QueryStatsResponse { stat }).await
    }
}
