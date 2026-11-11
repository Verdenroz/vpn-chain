package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText

/**
 * The status of a chain that is not up. The firewall outlives the chain it
 * guarded, so a down chain has to say whether the network is still held.
 */
internal fun downStatus(
    state: TunnelState,
    firewallLoaded: Boolean,
    detail: UiText? = null,
    carriedNothing: Boolean = false,
) = ChainStatus(state = state, detail = detail, carriedNothing = carriedNothing).withFirewall(firewallLoaded)

/** Updates a status when the firewall is loaded or lifted. The firewall never blocks a Connected chain. */
internal fun ChainStatus.withFirewall(loaded: Boolean): ChainStatus = when (state) {
    TunnelState.Connected -> this
    TunnelState.Connecting -> copy(networkBlocked = loaded)
    TunnelState.Disconnected, TunnelState.Error -> copy(
        networkBlocked = loaded,
        killSwitch = if (loaded) KillSwitchState.Active else KillSwitchState.Disabled,
    )
}
