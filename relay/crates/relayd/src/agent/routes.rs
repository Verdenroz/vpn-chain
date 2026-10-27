use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Extension, Path, State};
use axum::http::StatusCode;
use axum::middleware;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, patch, post};
use axum::{Json, Router};
use relay_jobs::{JobId, JobKind};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::task::spawn_blocking;

use super::enroll::enroll;
use super::guard::{BODY_LIMIT, authenticate};
use super::reply::ApiError;
use super::{Principal, Service};
use crate::clock::seconds;
use crate::{Error, Role, TOKEN_TTL, pairing, valid_name};

type Outcome = Result<Response, ApiError>;

pub fn router(service: Arc<Service>) -> Router {
    let signed = Router::new()
        .route("/v1/relay", get(relay))
        .route("/v1/devices", get(list_devices).post(add_device))
        .route(
            "/v1/devices/{name}",
            patch(rename_device).delete(revoke_device),
        )
        .route("/v1/devices/{name}/rekey", post(rekey_device))
        .route("/v1/devices/{name}/pair", post(pair_device))
        .route("/v1/pool/refill", post(refill_pool))
        .route("/v1/jobs", post(submit_job))
        .route("/v1/jobs/{id}", get(read_job))
        .route("/v1/jobs/{id}/confirm", post(confirm_job))
        .route("/v1/audit", get(read_audit))
        .layer(middleware::from_fn_with_state(
            service.clone(),
            authenticate,
        ));
    Router::new()
        .route(
            "/v1/enroll",
            post(enroll).layer(DefaultBodyLimit::max(BODY_LIMIT)),
        )
        .merge(signed)
        .with_state(service)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct AddDevice {
    name: String,
    role: Option<Role>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Rename {
    name: String,
}

/// One request being served: who asked, what for, and the audit entry it leaves.
struct Call {
    service: Arc<Service>,
    who: Principal,
    action: &'static str,
    target: Option<String>,
    quiet: bool,
}

impl Call {
    fn new(
        service: Arc<Service>,
        who: Principal,
        action: &'static str,
        target: Option<String>,
    ) -> Self {
        Self {
            service,
            who,
            action,
            target,
            quiet: false,
        }
    }

    /// A read that is audited only when it is refused or fails, so polling does not fill the log.
    fn quiet(self) -> Self {
        Self {
            quiet: true,
            ..self
        }
    }

    fn is_admin(&self) -> bool {
        self.who.role == Role::Admin
    }

    /// Runs `work` off the async threads if `allowed`, and audits how it went.
    async fn run<T: Send + 'static>(
        self,
        allowed: bool,
        work: impl FnOnce(&Service, &Principal) -> Result<T, Error> + Send + 'static,
    ) -> Result<T, ApiError> {
        if !allowed {
            self.record("forbidden");
            return Err(ApiError::forbidden());
        }
        let (service, who) = (self.service.clone(), self.who.clone());
        let done = spawn_blocking(move || work(&service, &who))
            .await
            .unwrap_or_else(|_| Err(Error::new("internal error")));
        if done.is_err() || !self.quiet {
            self.record(if done.is_ok() { "ok" } else { "failed" });
        }
        done.map_err(ApiError::from)
    }

    fn record(&self, result: &str) {
        self.service
            .audit
            .record(&self.who.key, self.action, self.target.as_deref(), result);
    }
}

fn known(name: String) -> Option<String> {
    valid_name(&name).then_some(name)
}

fn parsed<T: for<'de> Deserialize<'de>>(body: &Bytes) -> Result<T, Error> {
    serde_json::from_slice(body).map_err(|_| Error::invalid("malformed request body"))
}

fn queued(job: Option<JobId>, mut body: Value) -> Response {
    body["job"] = json!(job);
    let status = if job.is_some() {
        StatusCode::ACCEPTED
    } else {
        StatusCode::OK
    };
    (status, Json(body)).into_response()
}

async fn relay(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
) -> Outcome {
    let overview = Call::new(service, who, "relay.read", None)
        .quiet()
        .run(true, |service, _| {
            let relay = service.load()?;
            Ok(json!({
                "relayd": env!("CARGO_PKG_VERSION"),
                "xray": relay.state.xray.version,
                "devices": relay.state.devices.len(),
                "pool": relay.state.spare_short_ids.len(),
                "jobs": service.jobs.is_some(),
            }))
        })
        .await?;
    Ok(Json(overview).into_response())
}

async fn list_devices(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
) -> Outcome {
    let listed = Call::new(service, who, "devices.list", None)
        .quiet()
        .run(true, |service, who| {
            let devices: Vec<Value> = service
                .load()?
                .state
                .devices
                .into_iter()
                .filter(|device| who.role == Role::Admin || device.name == who.device)
                .map(|device| {
                    json!({
                        "name": device.name,
                        "role": device.role,
                        "added": device.added,
                        "up": device.up,
                        "down": device.down,
                        "seen": device.seen,
                    })
                })
                .collect();
            Ok(json!({ "devices": devices }))
        })
        .await?;
    Ok(Json(listed).into_response())
}

async fn add_device(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    body: Bytes,
) -> Outcome {
    let request = parsed::<AddDevice>(&body);
    let target = request.as_ref().ok().and_then(|r| known(r.name.clone()));
    let call = Call::new(service, who, "devices.add", target);
    let admin = call.is_admin();
    let (identity, _) = call
        .run(admin, move |service, _| {
            let request = request?;
            service.operate(|manager| {
                manager.issue(
                    &request.name,
                    request.role.unwrap_or(Role::Member),
                    false,
                    None,
                )
            })
        })
        .await?;
    Ok((StatusCode::CREATED, Json(json!({ "identity": identity }))).into_response())
}

async fn rename_device(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    Path(name): Path<String>,
    body: Bytes,
) -> Outcome {
    let call = Call::new(service, who, "devices.rename", known(name.clone()));
    let admin = call.is_admin();
    let request = parsed::<Rename>(&body);
    let renamed = call
        .run(admin, move |service, _| {
            let request = request?;
            service.operate(|manager| manager.rename(&name, &request.name))?;
            Ok(request.name)
        })
        .await?;
    Ok(Json(json!({ "name": renamed })).into_response())
}

async fn revoke_device(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    Path(name): Path<String>,
) -> Outcome {
    let call = Call::new(service, who, "devices.revoke", known(name.clone()));
    let admin = call.is_admin();
    let (counters_lost, job) = call
        .run(admin, move |service, who| {
            if who.device == name {
                return Err(Error::conflict(
                    "this device cannot cut itself off: the relay could be left without an admin",
                ));
            }
            service.operate(|manager| manager.revoke(&name, false))
        })
        .await?;
    Ok(queued(job, json!({ "countersLost": counters_lost })))
}

async fn rekey_device(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    Path(name): Path<String>,
) -> Outcome {
    let call = Call::new(service, who, "devices.rekey", known(name.clone()));
    let allowed = call.is_admin() || call.who.device == name;
    let (identity, _) = call
        .run(allowed, move |service, _| {
            service.operate(|manager| manager.reissue(&name, None))
        })
        .await?;
    Ok(Json(json!({ "identity": identity })).into_response())
}

async fn pair_device(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    Path(name): Path<String>,
) -> Outcome {
    let call = Call::new(service, who, "devices.pair", known(name.clone()));
    let admin = call.is_admin();
    let (link, expires) = call
        .run(admin, move |service, who| {
            if who.device == name {
                return Err(Error::conflict(
                    "this device cannot pair itself: its key would be replaced mid-request",
                ));
            }
            let expires = seconds() + TOKEN_TTL;
            let link =
                pairing::issue(&service.state, &service.secrets, &service.lock, Some(&name))?;
            Ok((link.expose().to_owned(), expires))
        })
        .await?;
    Ok(Json(json!({ "link": link, "expires": expires })).into_response())
}

async fn refill_pool(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
) -> Outcome {
    let call = Call::new(service, who, "pool.refill", None);
    let admin = call.is_admin();
    let (added, job) = call
        .run(admin, |service, _| {
            service.operate(|manager| manager.refill_pool())
        })
        .await?;
    Ok(queued(job, json!({ "added": added })))
}

async fn submit_job(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    body: Bytes,
) -> Outcome {
    let kind = parsed::<JobKind>(&body);
    let label = match &kind {
        Ok(JobKind::RestartXray) => Some("restart-xray".to_owned()),
        Ok(JobKind::SwapXray { version }) => Some(format!("swap-xray {}", version.as_str())),
        _ => None,
    };
    let call = Call::new(service, who, "jobs.submit", label);
    let admin = call.is_admin();
    let id = call
        .run(admin, move |service, _| {
            let kind = match kind? {
                kind @ (JobKind::RestartXray | JobKind::SwapXray { .. }) => kind,
                JobKind::ApplyConfig => {
                    return Err(Error::invalid("not a job a client may ask for"));
                }
            };
            service
                .jobs()?
                .submit(kind)
                .map_err(|error| Error::new(error.to_string()))
        })
        .await?;
    Ok((StatusCode::ACCEPTED, Json(json!({ "id": id }))).into_response())
}

fn job_id(id: String) -> Result<JobId, Error> {
    id.try_into()
        .map_err(|error: relay_jobs::Error| Error::invalid(error.to_string()))
}

async fn read_job(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    Path(id): Path<String>,
) -> Outcome {
    let id = job_id(id);
    let target = id.as_ref().ok().map(|id| id.to_string());
    let call = Call::new(service, who, "jobs.read", target).quiet();
    let admin = call.is_admin();
    let job = call
        .run(admin, move |service, _| {
            service
                .jobs()?
                .status(&id?)
                .map_err(|_| Error::missing("no such job"))
        })
        .await?;
    Ok(Json(job).into_response())
}

async fn confirm_job(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
    Path(id): Path<String>,
) -> Outcome {
    let id = job_id(id);
    let target = id.as_ref().ok().map(|id| id.to_string());
    let call = Call::new(service, who, "jobs.confirm", target);
    let admin = call.is_admin();
    let id = call
        .run(admin, move |service, _| {
            let (jobs, id) = (service.jobs()?, id?);
            jobs.status(&id)
                .map_err(|_| Error::missing("no such job"))?;
            jobs.confirm(&id)
                .map_err(|error| Error::conflict(error.to_string()))?;
            Ok(id)
        })
        .await?;
    Ok((StatusCode::ACCEPTED, Json(json!({ "id": id }))).into_response())
}

async fn read_audit(
    State(service): State<Arc<Service>>,
    Extension(who): Extension<Principal>,
) -> Outcome {
    let call = Call::new(service, who, "audit.read", None).quiet();
    let admin = call.is_admin();
    let entries = call.run(admin, |service, _| service.audit.recent()).await?;
    Ok(Json(json!({ "entries": entries })).into_response())
}
