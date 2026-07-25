package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.generated.resources.Res
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_vpn_permission_denied
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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

    override val killSwitchGuidanceSupported: Boolean = true
    override val alwaysOnDetected: StateFlow<Boolean> = TunnelBridge.alwaysOnDetected.asStateFlow()

    override fun openSystemVpnSettings() {
        context.startActivity(
            Intent(Settings.ACTION_VPN_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }

    override suspend fun start(configJson: String, killSwitchEnabled: Boolean) {
        // killSwitchEnabled is desktop-only (see TunnelController) - Android's
        // kill switch is the system Always-on VPN setting, unrelated to this.
        val current = TunnelBridge.status.value.state
        if (current == TunnelState.Connecting || current == TunnelState.Connected) return
        TunnelBridge.setState(TunnelState.Connecting)
        if (!VpnPermissionCoordinator.ensure(context)) {
            TunnelBridge.setState(TunnelState.Error, UiText.Resource(Res.string.tunnel_error_vpn_permission_denied))
            return
        }
        TunnelBridge.pendingConfig = configJson
        val intent = Intent(context, VpnChainService::class.java).apply {
            action = VpnChainService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    override suspend fun stop() {
        val intent = Intent(context, VpnChainService::class.java).apply {
            action = VpnChainService.ACTION_STOP
        }
        context.startService(intent)
    }
}
