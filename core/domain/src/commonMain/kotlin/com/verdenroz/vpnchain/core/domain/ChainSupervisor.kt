package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ConnectivityRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the supervisor is doing between a drop and a restored chain. */
sealed interface ReconnectState {
    data object Idle : ReconnectState
    data class Waiting(val attempt: Int, val delayMillis: Long) : ReconnectState
    data class Retrying(val attempt: Int) : ReconnectState
}

/**
 * Keeps the chain in the state the user asked for.
 *
 * Two jobs: bring the chain up at launch when asked, and put it back up after
 * an unexpected drop. The distinction that makes this safe is
 * [ChainRepository.connectionIntent] — a tunnel that is down because the user
 * said so is left alone.
 */
class ChainSupervisor(
    private val chain: ChainRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val network: ConnectivityRepository,
) {

    private val _state = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val state: StateFlow<ReconnectState> = _state.asStateFlow()

    suspend fun run(): Unit = coroutineScope {
        launch { connectOnLaunch() }
        launch { superviseDrops() }
    }

    private suspend fun connectOnLaunch() {
        if (!settings.settings.first().autoConnectOnLaunch) return
        if (profiles.profile.first() == null) return
        // Desktop can adopt a relay the CLI already started, so "launch" does
        // not imply "disconnected".
        if (chain.status.first().state != TunnelState.Disconnected) return
        chain.connect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun superviseDrops() {
        var attempt = 0
        chain.status
            .map { it.state }
            .distinctUntilChanged()
            .collectLatest { state ->
                when (state) {
                    TunnelState.Connected -> {
                        attempt = 0
                        _state.value = ReconnectState.Idle
                    }

                    TunnelState.Disconnected, TunnelState.Error -> {
                        // Loops rather than waiting for the next status change:
                        // a failed retry can leave the state at Error, which
                        // distinctUntilChanged would never re-emit.
                        while (shouldReconnect()) {
                            attempt++
                            val wait = ReconnectPolicy.delayForAttemptMillis(attempt)
                            _state.value = ReconnectState.Waiting(attempt, wait)
                            awaitBackoffOrNetworkReturn(wait)
                            // Re-checked after the wait: the user can disconnect
                            // during a backoff, and that has to win.
                            if (!shouldReconnect()) break
                            _state.value = ReconnectState.Retrying(attempt)
                            // Not cancellable: reaching Connecting re-emits and
                            // would otherwise cancel the very attempt in flight.
                            withContext(NonCancellable) { chain.reconnect() }
                        }
                        _state.value = ReconnectState.Idle
                    }

                    TunnelState.Connecting -> Unit
                }
            }
    }

    private suspend fun shouldReconnect(): Boolean =
        chain.connectionIntent.first() &&
            settings.settings.first().autoReconnect &&
            profiles.profile.first() != null

    /** Whichever comes first: the backoff elapsing, or the link returning. */
    private suspend fun awaitBackoffOrNetworkReturn(waitMillis: Long) {
        withTimeoutOrNull(waitMillis) {
            // dropWhile skips the current online run, so an already-online link
            // waits out the full backoff instead of retrying instantly.
            network.online.distinctUntilChanged().dropWhile { it }.first { it }
        }
    }
}
