package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import kotlinx.coroutines.channels.BufferOverflow
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
     * Renders a config for a start the app did not initiate. Installed by the
     * data layer, which owns the profile and settings the service cannot reach.
     * See [TunnelController.installConfigProvider].
     */
    @Volatile
    var configProvider: (suspend () -> String?)? = null

    suspend fun renderConfig(): String? = configProvider?.invoke()

    /**
     * Whether Always-on VPN lockdown is currently protecting this app. Live and
     * bidirectional on API 29+, where [VpnChainService] polls the real, queryable
     * `VpnService.isAlwaysOn()` and `isLockdownEnabled()`. The setting can be
     * flipped at any time while the service is already running, so a one-shot
     * detection can't be trusted. Below API 29, those queries don't exist, so
     * this falls back to the "launched with a null intent" heuristic via
     * [markAlwaysOnDetected] (one-way: once observed, assumed to stay true,
     * since there's no live signal to un-set it on those versions).
     */
    val alwaysOnDetected = MutableStateFlow(false)

    /** API 29+: pushes the real, current lockdown state — can go either direction. */
    fun setAlwaysOnLockdown(enabled: Boolean) {
        alwaysOnDetected.value = enabled
        status.update { it.copy(killSwitch = if (enabled) KillSwitchState.Active else KillSwitchState.Disabled) }
    }

    /** Destinations the engine has dialled, by name; empty when it saw only an address. */
    val destinations = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Session traffic/uptime, reset on every connect (see [SessionStats]). */
    val stats = MutableStateFlow(SessionStats())

    /**
     * Stops that came from the notification's Disconnect key or an OS revoke,
     * rather than from the app's own disconnect path. Buffered so the service
     * can signal without suspending on a libbox or system callback thread.
     */
    val userStops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun signalUserStop() {
        userStops.tryEmit(Unit)
    }

    // replacement here would silently reset kill-switch status on every state change.
    fun setState(state: TunnelState, detail: UiText? = null) {
        val wasConnected = status.value.state == TunnelState.Connected
        status.update { it.copy(state = state, detail = detail) }
        if (state == TunnelState.Connected && !wasConnected) {
            stats.value = SessionStats(connectedSinceMillis = System.currentTimeMillis())
        } else if (state != TunnelState.Connected) {
            stats.value = SessionStats()
        }
    }

    /** Fed by the service's libbox status stream while the engine runs. */
    fun updateTraffic(uplinkTotal: Long, downlinkTotal: Long, uplink: Long, downlink: Long) {
        stats.update {
            it.copy(
                uplinkBytes = uplinkTotal,
                downlinkBytes = downlinkTotal,
                uplinkBytesPerSecond = uplink,
                downlinkBytesPerSecond = downlink,
            )
        }
    }

    /**
     * This app's own lines. Mirrored to logcat because the Logs screen is
     * otherwise the only place they appear, leaving a session that failed while
     * unattended unreadable. Only for text written here, never [logRelay]'s.
     */
    fun log(line: String) {
        Log.i(TAG, line)
        logs.tryEmit(line)
    }

    /** Engine error text can name the server and ports, so only [summary] reaches logcat. */
    fun logFailure(summary: String, detail: String?) {
        Log.i(TAG, "$summary, see Logs")
        logs.tryEmit(if (detail == null) summary else "$summary: $detail")
    }

    /**
     * The relay's own stream. Kept out of logcat: it names every destination the
     * chain dials, and logcat is a shared buffer that `adb bugreport` captures
     * verbatim, where app-private storage is the only place that belongs.
     */
    fun logRelay(line: String) {
        logs.tryEmit(line)
    }

    /**
     * Called once per process from wherever a Context is first available. Below
     * API 29 only: above it the service's poll is the truth, and a stored value
     * would show a setting that may have changed while nothing was running.
     */
    fun loadAlwaysOnDetected(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || alwaysOnDetected.value) return
        val stored = prefs(context).getBoolean(KEY_ALWAYS_ON_DETECTED, false)
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
        prefs(context).edit().putBoolean(KEY_ALWAYS_ON_DETECTED, true).apply()
    }

    /**
     * Whether the user asked for the running session. Persisted because a sticky
     * restart after process death delivers a null intent, just as an Always-on
     * start does, and this is all that tells them apart below API 29. Scoped to
     * the boot it was set in, since no session survives a reboot.
     */
    fun setUserStarted(context: Context, started: Boolean) {
        prefs(context).edit().run {
            if (started) putInt(KEY_USER_STARTED_BOOT, bootCount(context)) else remove(KEY_USER_STARTED_BOOT)
        }.apply()
    }

    fun userStarted(context: Context): Boolean =
        prefs(context).getInt(KEY_USER_STARTED_BOOT, -1) == bootCount(context)

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val TAG = "TunnelBridge"
    private const val PREFS_NAME = "vpn_chain_tunnel_bridge"
    private const val KEY_ALWAYS_ON_DETECTED = "always_on_detected"
    private const val KEY_USER_STARTED_BOOT = "user_started_boot"
}
