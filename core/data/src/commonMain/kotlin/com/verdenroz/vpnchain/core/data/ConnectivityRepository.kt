package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.tunnel.NetworkMonitor
import kotlinx.coroutines.flow.Flow

/**
 * Underlying network reachability, independent of whether the chain is up.
 *
 * Exists so `core/domain` can react to the link returning without depending on
 * `core/tunnel` directly — the layering rule keeps platform plumbing behind a
 * repository interface.
 */
interface ConnectivityRepository {
    val online: Flow<Boolean>

    /** See [NetworkMonitor.linkChanges]: the link swapped under a chain that never went offline. */
    val linkChanges: Flow<Unit>
}

internal class DefaultConnectivityRepository(
    monitor: NetworkMonitor,
) : ConnectivityRepository {
    override val online: Flow<Boolean> = monitor.online
    override val linkChanges: Flow<Unit> = monitor.linkChanges
}
