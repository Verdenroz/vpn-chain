package com.verdenroz.vpnchain.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local sing-box control endpoint, used only to read traffic counters.
 *
 * [secret] is not optional in practice: the clash API can close connections and
 * switch outbounds, so an unauthenticated listener would let any local process
 * drive the tunnel. Bound to loopback and carried in memory rather than
 * persisted, so it dies with the session that created it.
 */
data class ClashApi(
    val port: Int,
    val secret: String,
) {
    val externalController: String get() = "127.0.0.1:$port"

    override fun toString() = "ClashApi(port=$port)"

    companion object {
        /** Recovers the API from a config; null when it has none, as a relay adopted from the CLI. */
        fun fromConfig(configJson: String): ClashApi? = runCatching {
            val api = Json.parseToJsonElement(configJson).jsonObject
                .getValue("experimental").jsonObject.getValue("clash_api").jsonObject
            ClashApi(
                port = api.getValue("external_controller").jsonPrimitive.content.substringAfterLast(':').toInt(),
                secret = api.getValue("secret").jsonPrimitive.content,
            )
        }.getOrNull()
    }
}
