package com.verdenroz.vpnchain.core.geoip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

internal actual suspend fun resolveHostToIp(host: String): String? = withContext(Dispatchers.IO) {
    runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
}
