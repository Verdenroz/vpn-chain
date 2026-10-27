package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Closes matching connections one by one through the clash API of the config the engine runs. */
internal class ClashConnectionCloser(private val runningConfig: StateFlow<String?>) : ConnectionCloser {

    override suspend fun close(matches: Collection<HostMatch>) = withContext(Dispatchers.IO) {
        val api = runningConfig.value?.let(ClashApi::fromConfig) ?: return@withContext
        val body = ClashStatsClient.fetchConnections(api) ?: throw IOException("clash API listed no connections")
        // An empty id would make the DELETE path `/connections/`, which closes every connection.
        val ids = parseUserConnections(body)
            .filter { connection -> connection.id.isNotEmpty() && matches.any { it.covers(connection.host) } }
            .map { it.id }
        val failure = ids.map { runCatching { delete(api, it) } }.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null) throw failure
    }

    private fun delete(api: ClashApi, id: String) {
        val connection = URI("http://${api.externalController}/connections/$id").toURL()
            .openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer ${api.secret}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            // A connection that ended after the listing answers 404.
            if (code !in 200..299 && code != HttpURLConnection.HTTP_NOT_FOUND) throw IOException("clash API answered $code")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
    }
}
