package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import kotlinx.coroutines.flow.Flow

/**
 * Remembers the public IP seen while untunnelled. Once the chain is up that
 * address is deliberately invisible from this machine, so the route panel can
 * only show where traffic *started* by recalling what it saw before.
 */
interface OriginRepository {
    val lastKnownOriginIp: Flow<String?>
    suspend fun record(ip: String)
}

internal class DefaultOriginRepository(
    private val preferences: VpnChainPreferencesDataSource,
) : OriginRepository {

    override val lastKnownOriginIp: Flow<String?> = preferences.lastOriginIp

    override suspend fun record(ip: String) = preferences.setLastOriginIp(ip)
}
