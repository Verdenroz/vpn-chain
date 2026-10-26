package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.flow.first

/**
 * A tunnel keeps dialling the config it started with, so after the active
 * connection changes anything the user wants up is moved onto it in place, never
 * stopped first: a stop would lift the kill switch for the whole reconnect.
 * That covers an attempt in flight and one the supervisor is about to retry.
 *
 * @return the reconnect result, or success when there was no tunnel to move.
 */
internal suspend fun ChainRepository.restartIfWanted(): Result<Unit> {
    val state = status.first().state
    val wantedUp = state == TunnelState.Connected || state == TunnelState.Connecting || connectionIntent.first()
    if (!wantedUp) return Result.success(Unit)
    return restart()
}
