package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import kotlinx.coroutines.flow.Flow

/**
 * Names of the destinations the running engine is dialling, from the engine's
 * own connection data. The app's probe traffic is left out.
 *
 * Desktop polls sing-box's clash API ([clashApi]); Android listens to libbox's
 * in-process connection events and has no use for it.
 */
interface DestinationFeed {
    fun hosts(clashApi: ClashApi?): Flow<String>
}
