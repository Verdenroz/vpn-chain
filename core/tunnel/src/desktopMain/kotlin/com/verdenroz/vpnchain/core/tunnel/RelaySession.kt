package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi

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
 * What the rendered sing-box config says about the session it would start.
 * Kept as text inspection rather than full deserialization: the controller only
 * needs the handful of facts that decide TUN vs relay, and which addresses the
 * kill switch must leave reachable.
 */
internal object RelayConfig {

    // ClashApi is defined in core:config alongside the factory that renders it.

    fun isTun(configJson: String): Boolean = TUN_INBOUND.containsMatchIn(configJson)

    fun hasWireGuardEntry(configJson: String): Boolean = WIREGUARD.containsMatchIn(configJson)

    fun proxyPort(configJson: String, default: Int): Int =
        LISTEN_PORT.find(configJson)?.groupValues?.get(1)?.toIntOrNull() ?: default

    /**
     * The VPS and, when present, the entry peer endpoint — the two addresses
     * sing-box's own uplink needs left open when everything else is blocked.
     * Getting this wrong strands the machine, so it returns only what it found.
     */
    fun exemptIps(configJson: String): List<String> =
        listOfNotNull(
            SERVER_IP.find(configJson)?.groupValues?.get(1),
            PEER_ADDRESS.find(configJson)?.groupValues?.get(1),
        ).distinct()

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
    private val WIREGUARD = Regex("\"type\"\\s*:\\s*\"wireguard\"")
    private val SERVER_IP = Regex("\"server\"\\s*:\\s*\"([0-9.]+)\"\\s*,\\s*\"server_port\"")
    private val PEER_ADDRESS = Regex("\"address\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"port\"")
}
