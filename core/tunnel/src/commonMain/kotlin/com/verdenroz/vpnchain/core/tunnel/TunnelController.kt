package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SessionStats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic control surface for the relay tunnel. Android backs this
 * with a VpnService + sing-box `libbox`; desktop shells out to the sing-box
 * binary. Both consume the JSON from [core.config.SingBoxConfigFactory].
 */
interface TunnelController {
    val status: StateFlow<ChainStatus>

    /** Raw output lines from the tunnel core, for the log screen. */
    val logs: SharedFlow<String>

    /**
     * @param killSwitchEnabled Desktop-only, WG-entry-hop-mode-only: whether to
     * install the nftables kill switch. Ignored everywhere else.
     */
    suspend fun start(configJson: String, killSwitchEnabled: Boolean = true)
    suspend fun stop()

    /**
     * Whether this platform has an OS-level kill switch worth guiding the user
     * toward. Neither platform lets an app enable one silently - Android's is a
     * system Settings toggle, desktop's is the external VPN app's own switch.
     */
    val killSwitchGuidanceSupported: Boolean get() = false

    /**
     * Traffic and uptime for the live session. Defaults to empty: a platform
     * with no way to report traffic is a missing readout, not a broken build.
     */
    val stats: StateFlow<SessionStats> get() = MutableStateFlow(SessionStats()).asStateFlow()

    /** True once we've observed the OS itself launch us as an always-on VPN. */
    val alwaysOnDetected: StateFlow<Boolean> get() = MutableStateFlow(false).asStateFlow()

    /**
     * Stops the user asked for through a surface that never reaches the app's
     * own disconnect path — Android's notification button, or an OS revoke.
     * Whoever tracks connection intent has to clear it on these, or they read
     * as a drop. Empty on platforms where every stop goes through [stop].
     */
    val userStops: SharedFlow<Unit> get() = MutableSharedFlow<Unit>().asSharedFlow()

    /** Opens the platform's VPN settings screen, if [killSwitchGuidanceSupported]. */
    fun openSystemVpnSettings() {}
}
