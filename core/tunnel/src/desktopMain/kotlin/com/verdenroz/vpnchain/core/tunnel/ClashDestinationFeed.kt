package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import java.net.HttpURLConnection
import java.net.URI
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
 * once, and pages keep connections open longer than that.
 */
internal class ClashDestinationFeed : DestinationFeed {

    override fun hosts(clashApi: ClashApi?): Flow<String> {
        if (clashApi == null) return emptyFlow()
        return flow {
            while (true) {
                fetchHosts(clashApi).forEach { emit(it) }
                delay(POLL_MS)
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun fetchHosts(api: ClashApi): Set<String> = runCatching {
        val connection = URI("http://${api.externalController}/connections").toURL()
            .openConnection() as HttpURLConnection
        connection.apply {
            setRequestProperty("Authorization", "Bearer ${api.secret}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            parseHosts(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptySet())

    companion object {
        private const val POLL_MS = 2_000L
        private const val TIMEOUT_MS = 2_000

        /** Probe inbounds are tagged `probe-*`; their connections are ours, not the user's. */
        fun parseHosts(body: String): Set<String> =
            Json.parseToJsonElement(body).jsonObject.getValue("connections").jsonArray
                .map { it.jsonObject.getValue("metadata").jsonObject }
                .filterNot { it["type"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("probe-") }
                .mapNotNull { it["host"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) }
                .toSet()
    }
}
