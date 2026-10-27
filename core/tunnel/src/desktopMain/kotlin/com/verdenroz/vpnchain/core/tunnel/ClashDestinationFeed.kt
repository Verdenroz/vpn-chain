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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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

        fun parseHosts(body: String): Set<String> = parseUserConnections(body).map { it.host }.toSet()
    }
}

internal data class ClashConnection(val id: String, val host: String)

/** Live connections with a sniffed name. Clash reports the inbound as `type/tag`; probe connections are ours, not the user's. */
internal fun parseUserConnections(body: String): List<ClashConnection> =
    // sing-box answers `"connections": null` while nothing is open.
    (Json.parseToJsonElement(body).jsonObject.getValue("connections") as? JsonArray).orEmpty()
        .map { it.jsonObject }
        .filterNot { ProbeProxy.isProbeInbound(it.metadata("type").substringAfter('/')) }
        .mapNotNull { connection ->
            connection.metadata("host").takeIf(String::isNotEmpty)
                ?.let { ClashConnection(connection["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), it) }
        }

private fun JsonObject.metadata(key: String): String =
    getValue("metadata").jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()
