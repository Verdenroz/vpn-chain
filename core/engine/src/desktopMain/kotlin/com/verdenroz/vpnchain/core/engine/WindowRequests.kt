package com.verdenroz.vpnchain.core.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Show-window requests from other launches; one is kept until the window collects it. */
class WindowRequests {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    val requests: Flow<Unit> = channel.receiveAsFlow()

    fun request() {
        channel.trySend(Unit)
    }
}
