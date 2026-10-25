package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/** One host opens many sockets, so each name passes once per collection, up to [SEEN_CAP] names. */
internal class AndroidDestinationFeed : DestinationFeed {
    override fun hosts(clashApi: ClashApi?): Flow<String> {
        val seen = HashSet<String>()
        return TunnelBridge.destinations.filter { host ->
            if (seen.size >= SEEN_CAP) seen.clear()
            host.isNotEmpty() && seen.add(host)
        }
    }

    private companion object {
        const val SEEN_CAP = 4_096
    }
}
