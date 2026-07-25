package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.ChainStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
     * system Settings toggle, desktop's is the real ProtonVPN app's own switch.
     */
    val killSwitchGuidanceSupported: Boolean get() = false

    /** True once we've observed the OS itself launch us as an always-on VPN. */
    val alwaysOnDetected: StateFlow<Boolean> get() = MutableStateFlow(false).asStateFlow()

    /** Opens the platform's VPN settings screen, if [killSwitchGuidanceSupported]. */
    fun openSystemVpnSettings() {}
}
