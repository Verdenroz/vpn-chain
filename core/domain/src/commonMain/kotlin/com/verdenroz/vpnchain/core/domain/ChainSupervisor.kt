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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the supervisor is doing between a drop and a restored chain. */
sealed interface ReconnectState {
    data object Idle : ReconnectState
    data class Waiting(val attempt: Int, val delayMillis: Long) : ReconnectState
    data class Retrying(val attempt: Int) : ReconnectState

    /** Repeated attempts on a usable link carried nothing: the relay is down or has cut this device off. Retries slow down. */
    data object CutOff : ReconnectState
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
    private val now: () -> Long,
) {

    private val _state = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val state: StateFlow<ReconnectState> = _state.asStateFlow()

    private val silentAttempts = ArrayDeque<Long>()

    suspend fun run(): Unit = coroutineScope {
        launch { connectOnLaunch() }
        launch { superviseDrops() }
        launch { clearCutOffOnNewProfile() }
    }

    private suspend fun clearCutOffOnNewProfile() {
        profiles.profile.distinctUntilChanged().collect {
            silentAttempts.clear()
            _state.value = ReconnectState.Idle
        }
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
                        silentAttempts.clear()
                        _state.value = ReconnectState.Idle
                    }

                    TunnelState.Disconnected, TunnelState.Error -> {
                        // Loops rather than waiting for the next status change:
                        // a failed retry can leave the state at Error, which
                        // distinctUntilChanged would never re-emit.
                        while (shouldReconnect()) {
                            attempt++
                            val slow = silentAttempts.size >= CUT_OFF_ATTEMPTS
                            val wait = if (slow) CUT_OFF_RETRY_MS else ReconnectPolicy.delayForAttemptMillis(attempt)
                            _state.value = if (slow) ReconnectState.CutOff else ReconnectState.Waiting(attempt, wait)
                            awaitBackoffOrNetworkReturn(wait)
                            awaitUsableLink()
                            // Re-checked after the wait: the user can disconnect
                            // during a backoff, and that has to win.
                            if (!shouldReconnect()) break
                            if (!slow) _state.value = ReconnectState.Retrying(attempt)
                            // Not cancellable: reaching Connecting re-emits and
                            // would otherwise cancel the very attempt in flight.
                            val result = withContext(NonCancellable) { chain.reconnect() }
                            if (isCutOff(result)) _state.value = ReconnectState.CutOff
                        }
                        _state.value = ReconnectState.Idle
                        // A drop nobody is going to retry must not leave the
                        // platform tunnel held: Android keeps its VpnService in
                        // the foreground across a drop precisely so a retry can
                        // happen, and something has to let go when none is coming.
                        if (state == TunnelState.Error && !shouldReconnect()) chain.release()
                    }

                    TunnelState.Connecting -> Unit
                }
            }
    }

    private suspend fun shouldReconnect(): Boolean =
        chain.connectionIntent.first() &&
            settings.settings.first().autoReconnect &&
            profiles.profile.first() != null

    /**
     * Whether consecutive attempts on a usable link all ended with the chain
     * carrying nothing. A relay that dropped this identity looks like its decoy
     * site, and a relay that is down fails the same way, so this slows retries
     * instead of stopping them. Any other outcome, or a link that is down,
     * restarts the count.
     */
    private suspend fun isCutOff(result: Result<Unit>): Boolean {
        if (result.isSuccess || !chain.status.first().carriedNothing || !network.online.first()) {
            silentAttempts.clear()
            return false
        }
        if (silentAttempts.size < CUT_OFF_ATTEMPTS) {
            val at = now()
            silentAttempts.addLast(at)
            while (at - silentAttempts.first() > CUT_OFF_WINDOW_MS) silentAttempts.removeFirst()
        }
        return silentAttempts.size >= CUT_OFF_ATTEMPTS
    }

    /**
     * Holds an attempt back until there is a link to dial on.
     *
     * Measured on a device: without this, an outage runs attempts that cannot
     * succeed, and each one holds the tunnel's wake lock until its readiness
     * check times out — longer than the capped backoff, so the CPU never gets
     * to sleep. Waiting costs nothing, because the link returning is already
     * what ends a backoff early.
     *
     * Capped rather than open-ended: a link that is up but never reports as
     * usable — a captive portal has no validated capability — must not be able
     * to stall reconnects forever.
     */
    private suspend fun awaitUsableLink() {
        if (network.online.first()) return
        withTimeoutOrNull(OFFLINE_WAIT_CAP_MS) { network.online.first { it } }
    }

    /** Whichever comes first: the backoff elapsing, or the link moving under us. */
    private suspend fun awaitBackoffOrNetworkReturn(waitMillis: Long) {
        withTimeoutOrNull(waitMillis) {
            merge(
                // dropWhile skips the current online run, so an already-online
                // link waits out the full backoff instead of retrying instantly.
                network.online.distinctUntilChanged().dropWhile { it }.filter { it }.map { },
                // A handover never leaves `online`, and it is the change most
                // worth retrying on: every socket the last attempt made is bound
                // to a link that is gone.
                network.linkChanges,
            ).first()
        }
    }

    private companion object {
        const val OFFLINE_WAIT_CAP_MS = 5 * 60 * 1000L
        const val CUT_OFF_ATTEMPTS = 3
        const val CUT_OFF_RETRY_MS = 2 * 60 * 1000L

        // One attempt can spend half a minute in its readiness check.
        const val CUT_OFF_WINDOW_MS = 5 * 60 * 1000L
    }
}
