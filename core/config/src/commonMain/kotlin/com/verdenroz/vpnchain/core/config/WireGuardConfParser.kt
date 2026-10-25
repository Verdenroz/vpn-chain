package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.WireGuardEntry

/** Why a WireGuard `.conf` was refused. The message is short enough to show as is. */
sealed class WireGuardConfError(message: String) : IllegalArgumentException(message) {
    class Missing(val key: String) : WireGuardConfError("Missing $key.")
    class Invalid(val key: String) : WireGuardConfError("Bad $key.")
    class PeerCount(val count: Int) : WireGuardConfError(
        if (count == 0) "Config has no peer." else "Config has $count peers. Use one.",
    )
    class SplitTunnel : WireGuardConfError("Only full-tunnel configs work. Set AllowedIPs to 0.0.0.0/0.")
}

/**
 * Parses the stock `wg-quick` `.conf` format (what providers hand out and QR
 * codes carry) into the WireGuard part of a connection. The app routes all
 * traffic through the tunnel, so a split-tunnel config is refused rather than
 * silently widened.
 */
object WireGuardConfParser {

    fun looksLikeConf(text: String): Boolean =
        text.lineSequence().any { it.trim().equals("[Interface]", ignoreCase = true) }

    fun parse(text: String): Result<WireGuardEntry> = runCatching { parseEntry(text) }

    private fun parseEntry(text: String): WireGuardEntry {
        val interfaceKeys = mutableMapOf<String, MutableList<String>>()
        val peers = mutableListOf<MutableMap<String, MutableList<String>>>()
        var section: MutableMap<String, MutableList<String>>? = null

        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter(String::isNotEmpty)
            .forEach { line ->
                when {
                    line.equals("[Interface]", ignoreCase = true) -> section = interfaceKeys
                    line.equals("[Peer]", ignoreCase = true) ->
                        section = mutableMapOf<String, MutableList<String>>().also(peers::add)
                    '=' in line -> section?.getOrPut(line.substringBefore('=').trim().lowercase()) { mutableListOf() }
                        ?.add(line.substringAfter('=').trim())
                }
            }

        if (peers.size != 1) throw WireGuardConfError.PeerCount(peers.size)
        val peer = peers.single()

        val privateKey = interfaceKeys.key("PrivateKey")
        val addresses = interfaceKeys.list("Address").map(::withPrefix)
        if (addresses.isEmpty()) throw WireGuardConfError.Missing("Address")
        val publicKey = peer.key("PublicKey")
        val allowedIps = peer.list("AllowedIPs")
        if (allowedIps.isEmpty()) throw WireGuardConfError.Missing("AllowedIPs")
        if ("0.0.0.0/0" !in allowedIps) throw WireGuardConfError.SplitTunnel()
        val (host, port) = parseEndpoint(peer.single("Endpoint") ?: throw WireGuardConfError.Missing("Endpoint"))

        return WireGuardEntry(
            privateKey = privateKey,
            address = addresses.joinToString(","),
            peerPublicKey = publicKey,
            endpointHost = host,
            endpointPort = port,
            dns = interfaceKeys.list("DNS").firstOrNull(),
            presharedKey = peer.single("PresharedKey")?.also { requireKey("PresharedKey", it) },
            mtu = interfaceKeys.single("MTU")?.let { number("MTU", it, min = 576) },
            keepaliveSeconds = peer.single("PersistentKeepalive")
                ?.let { number("PersistentKeepalive", it, min = 0) }
                ?.takeIf { it > 0 },
        )
    }

    private fun Map<String, List<String>>.single(key: String): String? =
        this[key.lowercase()]?.lastOrNull()?.takeIf(String::isNotEmpty)

    /** Repeatable keys may also carry several comma-separated values. */
    private fun Map<String, List<String>>.list(key: String): List<String> =
        this[key.lowercase()].orEmpty().flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty)

    private fun Map<String, List<String>>.key(name: String): String {
        val value = single(name) ?: throw WireGuardConfError.Missing(name)
        requireKey(name, value)
        return value
    }

    private fun requireKey(name: String, value: String) {
        if (!KEY_PATTERN.matches(value)) throw WireGuardConfError.Invalid(name)
    }

    private fun number(name: String, value: String, min: Int): Int =
        value.toIntOrNull()?.takeIf { it >= min } ?: throw WireGuardConfError.Invalid(name)

    /** sing-box wants a prefix on every interface address; wg-quick tolerates a bare one. */
    private fun withPrefix(address: String): String =
        if ('/' in address) address else if (':' in address) "$address/128" else "$address/32"

    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val host: String
        val rawPort: String
        if (endpoint.startsWith("[")) {
            host = endpoint.substringAfter('[').substringBefore(']')
            rawPort = endpoint.substringAfter("]:", missingDelimiterValue = "")
        } else {
            host = endpoint.substringBeforeLast(':')
            rawPort = endpoint.substringAfterLast(':', missingDelimiterValue = "")
        }
        val port = rawPort.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        if (host.isEmpty() || port == null) throw WireGuardConfError.Invalid("Endpoint")
        return host to port
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9+/]{43}=")
    private const val MAX_PORT = 65535
}
