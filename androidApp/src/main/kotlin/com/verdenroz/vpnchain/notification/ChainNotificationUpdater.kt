package com.verdenroz.vpnchain.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.HopEvidence
import com.verdenroz.vpnchain.core.domain.HopRole
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.tunnel.VpnChainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Enriches the connected foreground notification with live route data the
 * service can't reach (core/tunnel sits below core/domain): exit IP as the
 * headline, RTT and location as metrics, and a centered Disconnect key —
 * the stock action row can't be centered, so the layout is a custom view.
 */
class ChainNotificationUpdater(
    private val context: Context,
    private val chainRepository: ChainRepository,
    private val observeChainRoute: ObserveChainRouteUseCase,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            chainRepository.status.map { it.state }.distinctUntilChanged()
                .flatMapLatest { state ->
                    // Other states stay with the service's own notification
                    // (or its removal on disconnect).
                    if (state == TunnelState.Connected) observeChainRoute() else emptyFlow()
                }
                .collect { route ->
                    context.getSystemService(NotificationManager::class.java)
                        .notify(VpnChainService.NOTIFICATION_ID, build(route))
                }
        }
    }

    private fun build(route: ChainRoute): Notification {
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
            Intent(context, VpnChainService::class.java).setAction(VpnChainService.ACTION_STOP),
            flags,
        )

        val content = RemoteViews(context.packageName, R.layout.notification_chain).apply {
            setTextViewText(R.id.notification_title, route.exitHeadline())
            setTextViewText(R.id.notification_metrics, route.metricsLine())
            setOnClickPendingIntent(R.id.notification_disconnect, disconnect)
        }
        return Notification.Builder(context, VpnChainService.CHANNEL_ID)
            .setSmallIcon(com.verdenroz.vpnchain.core.tunnel.R.drawable.ic_vpn_chain_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(content)
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
}
