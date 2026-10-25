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
    override fun toString() = "ProbeProxy(relayPort=$relayPort, warpPort=$warpPort, fallbackPort=$fallbackPort)"

    companion object {
        private const val TAG_PREFIX = "probe-"
        const val RELAY_TAG = "${TAG_PREFIX}relay"
        const val WARP_TAG = "${TAG_PREFIX}warp"
        const val FALLBACK_TAG = "${TAG_PREFIX}fallback"

        /** The app's own probe traffic, which detection must not feed back to itself. */
        fun isProbeInbound(tag: String): Boolean = tag.startsWith(TAG_PREFIX)

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

/**
 * Fresh ports and credentials for one rendered config. A port is free when
 * picked, not when sing-box binds it, and sing-box fails its whole start when
 * any inbound cannot bind, so a start that loses that race renders again.
 */
expect fun newProbeProxy(): ProbeProxy
