package com.verdenroz.vpnchain.core.warp.probe

/**
 * What classification needs from a fetch, nothing more: the status, the few
 * headers that name a challenge, and the start of the body. [complete] is
 * false when the body was cut at the retention cap.
 */
data class ProbeResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val complete: Boolean,
) {
    companion object {
        /** Header names are lowercased; null when [raw] is not an HTTP response. */
        fun parse(raw: String, complete: Boolean): ProbeResponse? {
            val split = raw.indexOf("\r\n\r\n")
            if (split < 0) return null
            val lines = raw.substring(0, split).split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return null
            val headers = lines.drop(1)
                .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim().lowercase() to it[1].trim() }
            return ProbeResponse(status, headers, raw.substring(split + 4), complete)
        }
    }
}
