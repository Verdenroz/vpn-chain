package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A relay running outside this controller: our own leftover, another GUI run,
 * or the CLI's. Persisted beside the pidfile so a restarted app adopts the
 * session it finds instead of launching a second sing-box over the same TUN.
 */
internal data class RelaySession(
    val tun: Boolean,
    val entry: Boolean,
    val killSwitch: Boolean,
    /** The setting the session was started with — kept so an adopted session
     *  reports why it isn't protected, not just that it isn't. */
    val killSwitchEnabled: Boolean,
) {
    fun format(): String = buildString {
        append("tun=$tun\n")
        append("entry=$entry\n")
        append("killswitch=$killSwitch\n")
        append("killswitch_enabled=$killSwitchEnabled\n")
    }

    companion object {
        /** Null for anything unparseable: a corrupt file must read as "no known
         *  session", never as a session with silently defaulted fields. */
        fun parse(text: String): RelaySession? {
            val values = text.lineSequence()
                .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }
            if (REQUIRED_KEYS.any { it !in values }) return null
            return RelaySession(
                tun = values["tun"].toBoolean(),
                entry = values["entry"].toBoolean(),
                killSwitch = values["killswitch"].toBoolean(),
                killSwitchEnabled = values["killswitch_enabled"].toBoolean(),
            )
        }

        private val REQUIRED_KEYS = listOf("tun", "entry", "killswitch")
    }
}

/**
 * What the rendered sing-box config says about the session it would start —
 * the handful of facts that decide TUN vs relay, and which addresses the kill
 * switch must leave reachable. Mostly text inspection; the endpoints are
 * parsed properly, because telling one WireGuard hop from another by pattern
 * is exactly the kind of guess the kill switch can't afford.
 */
internal object RelayConfig {

    // ClashApi is defined in core:config alongside the factory that renders it.

    fun isTun(configJson: String): Boolean = TUN_INBOUND.containsMatchIn(configJson)

    /**
     * Scoped to the entry hop's own tag, not to "a wireguard endpoint exists":
     * the WARP tail is a WireGuard endpoint too, and counting it as an entry
     * hop would have the readout claim a hop the chain doesn't have.
     */
    fun hasWireGuardEntry(configJson: String): Boolean = entryHopEndpoint(configJson) != null

    fun proxyPort(configJson: String, default: Int): Int =
        LISTEN_PORT.find(configJson)?.groupValues?.get(1)?.toIntOrNull() ?: default

    /**
     * The VPS and, when present, the entry peer endpoint — the two addresses
     * sing-box's own uplink needs left open when everything else is blocked.
     * Getting this wrong strands the machine, so it returns only what it found.
     *
     * The WARP tail is deliberately absent: it is dialled *through* the relay,
     * so its packets are already inside the tunnel, and exempting Cloudflare's
     * anycast address would punch a hole nothing needs.
     */
    fun exemptIps(configJson: String): List<String> =
        listOfNotNull(
            SERVER_IP.find(configJson)?.groupValues?.get(1),
            entryPeerAddress(configJson),
        ).distinct()

    /**
     * Parsed rather than pattern-matched: with a second WireGuard endpoint in
     * play, "the first address followed by a port" is whichever one happens to
     * render first, and this decides what the kill switch leaves reachable.
     */
    private fun entryHopEndpoint(configJson: String): JsonObject? = runCatching {
        val root = Json.parseToJsonElement(configJson).jsonObject
        // Older configs carried the peer as an outbound; sing-box 1.11+ moved
        // it to `endpoints`, and an adopted relay may predate that.
        listOf("endpoints", "outbounds")
            .flatMap { key -> root[key]?.jsonArray.orEmpty().map { it.jsonObject } }
            .firstOrNull { it["tag"]?.jsonPrimitive?.contentOrNull == ENTRY_HOP_TAG }
    }.getOrNull()

    private fun entryPeerAddress(configJson: String): String? = runCatching {
        entryHopEndpoint(configJson)
            ?.get("peers")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("address")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private const val ENTRY_HOP_TAG = "entry-hop"

    /**
     * The clash API this config exposes, if any. A relay adopted from the CLI
     * renders no such block, so absence is normal and simply means no stats.
     */
    fun clashApi(configJson: String): ClashApi? {
        val port = CLASH_CONTROLLER.find(configJson)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val secret = CLASH_SECRET.find(configJson)?.groupValues?.get(1) ?: return null
        return ClashApi(port = port, secret = secret)
    }

    private val LISTEN_PORT = Regex("\"listen_port\"\\s*:\\s*(\\d+)")
    private val CLASH_CONTROLLER = Regex("\"external_controller\"\\s*:\\s*\"127\\.0\\.0\\.1:(\\d+)\"")
    private val CLASH_SECRET = Regex("\"secret\"\\s*:\\s*\"([^\"]+)\"")
    private val TUN_INBOUND = Regex("\"type\"\\s*:\\s*\"tun\"")
    private val SERVER_IP = Regex("\"server\"\\s*:\\s*\"([0-9.]+)\"\\s*,\\s*\"server_port\"")
}
