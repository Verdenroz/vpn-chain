package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

internal class AndroidDestinationFeed : DestinationFeed {
    override fun hosts(clashApi: ClashApi?): Flow<String> =
        TunnelBridge.destinations.filter { it.isNotEmpty() }
}
