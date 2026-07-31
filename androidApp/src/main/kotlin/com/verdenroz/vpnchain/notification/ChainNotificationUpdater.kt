package com.verdenroz.vpnchain.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.core.common.formatBytes
import com.verdenroz.vpnchain.core.common.formatRate
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.HopEvidence
import com.verdenroz.vpnchain.core.domain.HopRole
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.tunnel.VpnChainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Enriches the connected foreground notification with live telemetry the
 * service can't reach (core/tunnel sits below core/domain): exit IP as the
 * headline, RTT/location/traffic as metrics, a self-ticking uptime
 * chronometer, and a centered Disconnect key — the stock action row can't be
 * centered, so the layout is a custom view.
 */
class ChainNotificationUpdater(
    private val context: Context,
    private val chainRepository: ChainRepository,
    private val observeChainRoute: ObserveChainRouteUseCase,
) {

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            chainRepository.status.map { it.state }.distinctUntilChanged()
                .flatMapLatest { state ->
                    // Other states stay with the service's own notification
                    // (or its removal on disconnect).
                    if (state == TunnelState.Connected) telemetry() else emptyFlow()
                }
                .collect { (route, stats) ->
                    // The service owns this notification outside Connected — it
                    // says "reconnecting" there. A telemetry frame already in
                    // flight when the chain drops must not paint over that.
                    if (chainRepository.status.first().state != TunnelState.Connected) return@collect
                    context.getSystemService(NotificationManager::class.java)
                        .notify(VpnChainService.NOTIFICATION_ID, build(route, stats))
                }
        }
    }

    /**
     * Stats tick every second in the engine; reposting the notification that
     * often is churn the shade punishes, so traffic is sampled and uptime is
     * left to the chronometer, which ticks on its own.
     */
    @OptIn(FlowPreview::class)
    private fun telemetry() = combine(
        observeChainRoute(),
        chainRepository.stats.sample(STATS_SAMPLE_MS)
            .onStart { emit(chainRepository.stats.first()) },
    ) { route, stats -> route to stats }

    private fun build(route: ChainRoute, stats: SessionStats): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            flags,
        )
        val disconnect = PendingIntent.getService(
            context,
            1,
            Intent(context, VpnChainService::class.java)
                .setAction(VpnChainService.ACTION_STOP_BY_USER),
            flags,
        )

        val collapsed = RemoteViews(context.packageName, R.layout.notification_chain).apply {
            setTextViewText(R.id.notification_title, route.exitHeadline())
            setTextViewText(R.id.notification_metrics, route.metricsLine())
            setOnClickPendingIntent(R.id.notification_disconnect, disconnect)
        }
        val expanded = RemoteViews(context.packageName, R.layout.notification_chain_big).apply {
            setTextViewText(R.id.notification_title, route.exitHeadline())
            setTextViewText(R.id.notification_metrics, route.metricsLine())
            setTextViewText(R.id.notification_traffic, stats.trafficLine())
            stats.connectedSinceMillis?.let { since ->
                setChronometer(
                    R.id.notification_uptime,
                    SystemClock.elapsedRealtime() - (System.currentTimeMillis() - since),
                    context.getString(R.string.notification_uptime_format),
                    true,
                )
            }
            setOnClickPendingIntent(R.id.notification_disconnect, disconnect)
        }
        return Notification.Builder(context, VpnChainService.CHANNEL_ID)
            .setSmallIcon(com.verdenroz.vpnchain.core.tunnel.R.drawable.ic_vpn_chain_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .build()
    }

    private fun ChainRoute.exitHeadline(): String =
        hop(HopRole.Exit)?.ip ?: context.getString(R.string.notification_exit_resolving)

    /** Only measured or honestly-labeled values — never invented metrics. */
    private fun ChainRoute.metricsLine(): String {
        val exit = hop(HopRole.Exit)
        val parts = buildList {
            throughRttMs?.let { add(context.getString(R.string.notification_rtt, it)) }
            exit?.location?.countryName?.let { add(it) }
            when (exit?.evidence) {
                HopEvidence.Configured -> add(context.getString(R.string.notification_evidence_configured))
                HopEvidence.Recalled -> add(context.getString(R.string.notification_evidence_recalled))
                else -> Unit
            }
        }
        return if (parts.isEmpty()) context.getString(R.string.notification_measuring) else parts.joinToString(" · ")
    }

    private fun SessionStats.trafficLine(): String =
        if (hasTraffic) {
            context.getString(
                R.string.notification_traffic,
                formatRate(downlinkBytesPerSecond),
                formatRate(uplinkBytesPerSecond),
                formatBytes(uplinkBytes + downlinkBytes),
            )
        } else {
            context.getString(R.string.notification_measuring)
        }

    private companion object {
        const val STATS_SAMPLE_MS = 10_000L
    }
}
