package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Covers the engine boot plus the service's readiness check (five probes of up to seven seconds). */
val CONNECT_TIMEOUT: Duration = 60.seconds

/** A start or restart that did not end in [TunnelState.Connected]; [state] is Connecting when it timed out. */
class TunnelConnectException(val state: TunnelState, val detail: UiText?) :
    Exception("The tunnel did not connect (${state.name.lowercase()})")

/**
 * Suspends until the tunnel leaves [TunnelState.Connecting]. The caller must already have
 * moved the status to Connecting, or a Connected left by the previous engine satisfies it.
 *
 * @throws TunnelConnectException on Error, on the user's Disconnect, or after [timeout].
 */
suspend fun StateFlow<ChainStatus>.awaitConnected(timeout: Duration = CONNECT_TIMEOUT) {
    val settled = withTimeoutOrNull(timeout) { first { it.state != TunnelState.Connecting } }
        ?: throw TunnelConnectException(TunnelState.Connecting, null)
    if (settled.state != TunnelState.Connected) throw TunnelConnectException(settled.state, settled.detail)
}
