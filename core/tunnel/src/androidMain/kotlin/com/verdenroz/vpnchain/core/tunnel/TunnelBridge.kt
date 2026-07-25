package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide channel between [AndroidTunnelController] (in the UI) and
 * [VpnChainService] (its own lifecycle). Safe because Android allows only one
 * active VpnService, so there is exactly one tunnel to track.
 */
internal object TunnelBridge {
    val status = MutableStateFlow(ChainStatus())
    // replay so the Logs screen shows recent history even if it subscribes late.
    val logs = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 512)

    /** Config handed from the controller to the service via [android.content.Intent] start. */
    @Volatile
    var pendingConfig: String? = null

    /**
     * Whether Always-on VPN lockdown is currently protecting this app. Live and
     * bidirectional on API 29+, where [VpnChainService] polls the real, queryable
     * `VpnService.isLockdownEnabled()` — the setting can be flipped at any time
     * while the service is already running, so a one-shot detection can't be
     * trusted. Below API 29, that query doesn't exist, so this falls back to the
     * older "launched with a null intent" heuristic via [markAlwaysOnDetected]
     * (one-way: once observed, assumed to stay true, since there's no live
     * signal to un-set it on those versions).
     */
    val alwaysOnDetected = MutableStateFlow(false)

    /** API 29+: pushes the real, current lockdown state — can go either direction. */
    fun setAlwaysOnLockdown(enabled: Boolean) {
        alwaysOnDetected.value = enabled
        status.update { it.copy(killSwitch = if (enabled) KillSwitchState.Active else KillSwitchState.Disabled) }
    }

    // replacement here would silently reset kill-switch status on every state change.
    fun setState(state: TunnelState, detail: UiText? = null) {
        status.update { it.copy(state = state, detail = detail) }
    }

    fun log(line: String) {
        logs.tryEmit(line)
    }

    /** Called once per process from wherever a Context is first available. */
    fun loadAlwaysOnDetected(context: Context) {
        if (alwaysOnDetected.value) return
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_ON_DETECTED, false)
        if (stored) {
            alwaysOnDetected.value = true
            status.update { it.copy(killSwitch = KillSwitchState.Active) }
        }
    }

    /** Called by [VpnChainService] when it observes a null-intent (always-on) start. */
    fun markAlwaysOnDetected(context: Context) {
        if (alwaysOnDetected.value) return
        alwaysOnDetected.value = true
        status.update { it.copy(killSwitch = KillSwitchState.Active) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALWAYS_ON_DETECTED, true).apply()
    }

    private const val PREFS_NAME = "vpn_chain_tunnel_bridge"
    private const val KEY_ALWAYS_ON_DETECTED = "always_on_detected"
}
