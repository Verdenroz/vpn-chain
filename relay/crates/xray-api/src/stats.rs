use std::collections::BTreeMap;

use crate::proto::xray::app::stats::command::{QueryStatsRequest, Stat};
use crate::{Error, XrayApi};

/// The pattern that selects every user's counters.
pub const ALL_USERS: &str = "user>>>";

/// Bytes one user moved through the relay since the counters were last reset.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Traffic {
    pub up: u64,
    pub down: u64,
}

impl XrayApi {
    /// Per-user traffic keyed by email, from the counters whose names contain
    /// `pattern`. With `reset`, xray zeroes each counter as it reads it, so
    /// the caller owns the only copy of those bytes and must persist them.
    pub async fn user_traffic(
        &self,
        pattern: &str,
        reset: bool,
    ) -> Result<BTreeMap<String, Traffic>, Error> {
        let request = QueryStatsRequest {
            pattern: pattern.to_owned(),
            reset,
        };
        let response = self.within(self.stats.clone().query_stats(request)).await?;
        Ok(fold(response.stat))
    }
}

fn fold(stats: Vec<Stat>) -> BTreeMap<String, Traffic> {
    stats.into_iter().fold(BTreeMap::new(), |mut users, stat| {
        let counter = stat
            .name
            .strip_prefix("user>>>")
            .and_then(|rest| rest.rsplit_once(">>>traffic>>>"));
        let bytes = u64::try_from(stat.value).unwrap_or_default();
        match counter {
            Some((email, "uplink")) => users.entry(email.to_owned()).or_default().up += bytes,
            Some((email, "downlink")) => users.entry(email.to_owned()).or_default().down += bytes,
            _ => {}
        }
        users
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fake::{FakeXray, Reply};

    fn stat(name: &str, value: i64) -> Stat {
        Stat {
            name: name.to_owned(),
            value,
        }
    }

    #[test]
    fn fold_keys_uplink_and_downlink_by_email() {
        let users = fold(vec![
            stat("user>>>laptop>>>traffic>>>uplink", 120),
            stat("user>>>laptop>>>traffic>>>downlink", 4096),
            stat("user>>>phone>>>traffic>>>downlink", 7),
        ]);
        assert_eq!(
            users["laptop"],
            Traffic {
                up: 120,
                down: 4096
            }
        );
        assert_eq!(users["phone"], Traffic { up: 0, down: 7 });
    }

    #[test]
    fn fold_skips_counters_that_are_not_user_traffic() {
        let users = fold(vec![
            stat("inbound>>>vless-reality-443>>>traffic>>>uplink", 9),
            stat("user>>>laptop>>>online", 1),
            stat("user>>>laptop>>>traffic>>>sideways", 3),
        ]);
        assert!(users.is_empty());
    }

    #[test]
    fn fold_reports_a_reset_counter_as_zero() {
        let users = fold(vec![stat("user>>>laptop>>>traffic>>>uplink", 0)]);
        assert_eq!(users["laptop"], Traffic::default());
    }

    #[tokio::test]
    async fn user_traffic_passes_pattern_and_reset() {
        let xray = FakeXray::start(Reply::Ok).await;
        xray.set_stats(vec![stat("user>>>laptop>>>traffic>>>uplink", 5)]);
        let users = xray.api().user_traffic(ALL_USERS, true).await.unwrap();

        let request = xray.last_query().unwrap();
        assert_eq!(request.pattern, "user>>>");
        assert!(request.reset);
        assert_eq!(users["laptop"].up, 5);
    }

    #[tokio::test]
    async fn slow_stats_time_out() {
        let xray = FakeXray::start(Reply::Stall).await;
        let error = xray.api().user_traffic(ALL_USERS, false).await.unwrap_err();
        assert!(matches!(error, Error::Timeout(_)));
    }
}
