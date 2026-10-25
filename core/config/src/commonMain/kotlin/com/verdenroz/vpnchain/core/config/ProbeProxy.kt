package com.verdenroz.vpnchain.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loopback-only proxies the app fetches through to see how a site treats each
 * exit: [relayPort] leaves from the relay, [warpPort] from the WARP tail, and
 * [fallbackPort] from the fail-open path (entry hop, or the bare link without
 * one). A port is 0 when the config has no such exit. The credentials are per
 * render, so another local process can't borrow an exit.
 */
data class ProbeProxy(
    val relayPort: Int,
    val warpPort: Int,
    val fallbackPort: Int,
    val username: String,
    val password: String,
) {
    companion object {
        const val RELAY_TAG = "probe-relay"
        const val WARP_TAG = "probe-warp"
        const val FALLBACK_TAG = "probe-fallback"

        /** Recovers the probes from a rendered config; null when it has none. */
        fun fromConfig(configJson: String): ProbeProxy? = runCatching {
            val inbounds = Json.parseToJsonElement(configJson).jsonObject
                .getValue("inbounds").jsonArray.map { it.jsonObject }
                .associateBy { it["tag"]?.jsonPrimitive?.contentOrNull }
            val relay = inbounds.getValue(RELAY_TAG)
            val user = relay.getValue("users").jsonArray.single().jsonObject
            ProbeProxy(
                relayPort = relay.getValue("listen_port").jsonPrimitive.int,
                warpPort = inbounds[WARP_TAG]?.getValue("listen_port")?.jsonPrimitive?.int ?: 0,
                fallbackPort = inbounds[FALLBACK_TAG]?.getValue("listen_port")?.jsonPrimitive?.int ?: 0,
                username = user.getValue("username").jsonPrimitive.content,
                password = user.getValue("password").jsonPrimitive.content,
            )
        }.getOrNull()
    }
}

/** Fresh ports and credentials for one rendered config. */
expect fun newProbeProxy(): ProbeProxy
