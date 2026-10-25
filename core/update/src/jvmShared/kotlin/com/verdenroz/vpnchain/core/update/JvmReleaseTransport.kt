package com.verdenroz.vpnchain.core.update

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.IOException

internal class JvmReleaseTransport : ReleaseTransport {

    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        open(url).inputStream.bufferedReader().use { it.readText() }
    }

    override suspend fun download(url: String, sink: BufferedSink, onProgress: (Long) -> Unit) {
        withContext(Dispatchers.IO) {
            open(url).inputStream.use { input ->
                val chunk = ByteArray(CHUNK_BYTES)
                var received = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    sink.write(chunk, 0, read)
                    received += read
                    onProgress(received)
                }
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        return connection
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val CHUNK_BYTES = 64 * 1024
    }
}
