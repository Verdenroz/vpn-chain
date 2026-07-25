package com.verdenroz.vpnchain.core.geoip

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createNetworkProbe(): NetworkProbe = AndroidNetworkProbe()

internal class AndroidNetworkProbe : NetworkProbe {

    override suspend fun samplePublicIp(): PublicIpSample? = withContext(Dispatchers.IO) {
        runCatching {
            val startedNs = System.nanoTime()
            val conn = URI(PUBLIC_IP_URL).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Cache-Control", "no-cache")
            val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).toInt()
            if (ip.isBlank()) null else PublicIpSample(ip, elapsedMs)
        }.getOrNull()
    }

    private companion object {
        const val PUBLIC_IP_URL = "https://api.ipify.org"
        const val TIMEOUT_MS = 8_000
    }
}
