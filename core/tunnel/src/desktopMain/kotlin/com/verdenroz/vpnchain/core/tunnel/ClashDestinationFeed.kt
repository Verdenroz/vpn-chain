package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import com.verdenroz.vpnchain.core.config.ProbeProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Polls `/connections`, which lists only live connections, so a connection
 * shorter than [POLL_MS] can be missed. Detection only needs a site to show up
 * once, and pages keep connections open longer than that. A host is emitted
 * when it appears, not again on every poll while it stays connected.
 */
internal class ClashDestinationFeed : DestinationFeed {

    override fun hosts(clashApi: ClashApi?): Flow<String> {
        if (clashApi == null) return emptyFlow()
        return flow {
            var previous = emptySet<String>()
            while (true) {
                val current = fetchHosts(clashApi)
                (current - previous).forEach { emit(it) }
                previous = current
                delay(POLL_MS)
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun fetchHosts(api: ClashApi): Set<String> =
        ClashStatsClient.fetchConnections(api)?.let { runCatching { parseHosts(it) }.getOrNull() }.orEmpty()

    companion object {
        private const val POLL_MS = 2_000L

        /** Clash reports the inbound as `type/tag`; probe connections are ours, not the user's. */
        fun parseHosts(body: String): Set<String> =
            Json.parseToJsonElement(body).jsonObject.getValue("connections").jsonArray
                .map { it.jsonObject.getValue("metadata").jsonObject }
                .filterNot { ProbeProxy.isProbeInbound(it["type"]?.jsonPrimitive?.contentOrNull.orEmpty().substringAfter('/')) }
                .mapNotNull { it["host"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) }
                .toSet()
    }
}
