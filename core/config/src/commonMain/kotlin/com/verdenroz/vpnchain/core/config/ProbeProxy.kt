package com.verdenroz.vpnchain.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Two loopback-only proxies the app fetches through to see how a site treats
 * each exit: [relayPort] leaves from the relay, [warpPort] from the WARP tail.
 * The credentials are per render, so another local process can't borrow either
 * exit.
 */
data class ProbeProxy(
    val relayPort: Int,
    val warpPort: Int,
    val username: String,
    val password: String,
) {
    companion object {
        const val RELAY_TAG = "probe-relay"
        const val WARP_TAG = "probe-warp"

        /**
         * Recovers the probes from a rendered config; null when it has none.
         * The warp port is 0 when the config carries no tail to probe through.
         */
        fun fromConfig(configJson: String): ProbeProxy? = runCatching {
            val inbounds = Json.parseToJsonElement(configJson).jsonObject
                .getValue("inbounds").jsonArray.map { it.jsonObject }
                .associateBy { it["tag"]?.jsonPrimitive?.contentOrNull }
            val relay = inbounds.getValue(RELAY_TAG)
            val user = relay.getValue("users").jsonArray.single().jsonObject
            ProbeProxy(
                relayPort = relay.getValue("listen_port").jsonPrimitive.int,
                warpPort = inbounds[WARP_TAG]?.getValue("listen_port")?.jsonPrimitive?.int ?: 0,
                username = user.getValue("username").jsonPrimitive.content,
                password = user.getValue("password").jsonPrimitive.content,
            )
        }.getOrNull()
    }
}

/** Fresh ports and credentials for one rendered config. */
expect fun newProbeProxy(): ProbeProxy
