package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.generated.resources.Res
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_background_start_refused
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_vpn_permission_denied
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives [VpnChainService] from the UI process. Ensures VPN consent, hands the
 * rendered config to the service via [TunnelBridge], and mirrors the service's
 * status/logs (both processes share the same bridge singleton).
 */
class AndroidTunnelController(
    private val context: Context,
) : TunnelController {

    init {
        TunnelBridge.loadAlwaysOnDetected(context)
    }

    override val status: StateFlow<ChainStatus> = TunnelBridge.status.asStateFlow()
    override val logs: SharedFlow<String> = TunnelBridge.logs
    override val stats: StateFlow<SessionStats> = TunnelBridge.stats.asStateFlow()
    override val runningConfig: StateFlow<String?> = TunnelBridge.runningConfig.asStateFlow()
    override val userStops: SharedFlow<Unit> = TunnelBridge.userStops.asSharedFlow()

    override val killSwitchGuidanceSupported: Boolean = true
    override val alwaysOnDetected: StateFlow<Boolean> = TunnelBridge.alwaysOnDetected.asStateFlow()

    override fun openSystemVpnSettings() {
        context.startActivity(
            Intent(Settings.ACTION_VPN_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }

    override fun installConfigProvider(provider: suspend () -> String?) {
        TunnelBridge.configProvider = provider
    }

    override suspend fun start(configJson: String, killSwitchEnabled: Boolean, allowLocalNetworks: Boolean) {
        // killSwitchEnabled is desktop-only (see TunnelController) - Android's
        // kill switch is the system Always-on VPN setting, unrelated to this.
        val current = TunnelBridge.status.value.state
        if (engineRunning(current)) return TunnelBridge.status.awaitConnected()
        TunnelBridge.beginAttempt()
        if (!VpnPermissionCoordinator.ensure(context)) {
            TunnelBridge.setState(TunnelState.Error, UiText.Resource(Res.string.tunnel_error_vpn_permission_denied))
            return TunnelBridge.status.awaitConnected()
        }
        TunnelBridge.pendingConfig = configJson
        val intent = Intent(context, VpnChainService::class.java).apply {
            action = VpnChainService.ACTION_START
        }
        // API 31+ refuses a foreground service started from the background
        runCatching { context.startForegroundService(intent) }
            .onFailure {
                TunnelBridge.setState(
                    TunnelState.Error,
                    UiText.Resource(Res.string.tunnel_error_background_start_refused),
                )
            }
        TunnelBridge.status.awaitConnected()
    }

    /**
     * Reloads the engine inside the running VpnService, so Always-on lockdown
     * never sees it stop. With no engine to reload it is a fresh start, which
     * needs consent first.
     */
    override suspend fun restart(configJson: String, killSwitchEnabled: Boolean, allowLocalNetworks: Boolean) {
        if (!engineRunning(TunnelBridge.status.value.state)) {
            return start(configJson, killSwitchEnabled, allowLocalNetworks)
        }
        TunnelBridge.beginAttempt()
        TunnelBridge.pendingConfig = configJson
        val intent = Intent(context, VpnChainService::class.java).apply {
            action = VpnChainService.ACTION_RELOAD
        }
        runCatching { context.startForegroundService(intent) }
            .onFailure {
                TunnelBridge.setState(
                    TunnelState.Error,
                    UiText.Resource(Res.string.tunnel_error_background_start_refused),
                )
            }
        TunnelBridge.status.awaitConnected()
    }

    /**
     * The same teardown as [stop]. The service keeps its foreground notification
     * across a drop so a reconnect is possible at all; when none is coming, that
     * notification is a promise nothing will keep.
     */
    override suspend fun release() = stop()

    override suspend fun stop() {
        val intent = Intent(context, VpnChainService::class.java).apply {
            action = VpnChainService.ACTION_STOP
        }
        context.startService(intent)
    }
}

/** Connecting or Connected means the service holds an engine, or a boot that will produce one. */
internal fun engineRunning(state: TunnelState): Boolean =
    state == TunnelState.Connecting || state == TunnelState.Connected
