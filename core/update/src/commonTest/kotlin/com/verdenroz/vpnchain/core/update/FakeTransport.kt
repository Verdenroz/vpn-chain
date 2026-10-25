package com.verdenroz.vpnchain.core.update

import kotlinx.coroutines.yield
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

internal class FakeTransport(
    private val pages: Map<String, String>,
    private val chunk: Int = 4,
) : ReleaseTransport {
    val requested = mutableListOf<String>()

    override suspend fun fetchText(url: String, maxBytes: Int): String {
        requested += url
        val page = pages[url] ?: throw IOException("HTTP 404")
        if (page.encodeUtf8().size > maxBytes) throw IOException("too large")
        return page
    }

    override suspend fun download(url: String, sink: BufferedSink, onProgress: (Long) -> Unit) {
        requested += url
        val body = pages[url] ?: throw IOException("HTTP 404")
        var received = 0L
        body.encodeUtf8().toByteArray().asList().chunked(chunk).forEach {
            sink.write(it.toByteArray())
            received += it.size
            onProgress(received)
            yield()
        }
    }
}
