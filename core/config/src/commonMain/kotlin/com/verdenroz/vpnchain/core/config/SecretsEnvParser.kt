package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.WireGuardEntry

/**
 * Parses the CLI's `secrets.env` format (see examples/secrets.env.example)
 * so the GUI apps can import the exact same file the CLI runs from. The
 * `ENTRY_*` keys are optional, take any WireGuard peer, and are only used by
 * the TUN-mode entry hop.
 */
object SecretsEnvParser {

    fun parse(text: String): Result<ChainProfile> {
        val values = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim().removeSurrounding("\"")
                key to value
            }

        val missing = REQUIRED_KEYS.filter { values[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException("Missing required keys: ${missing.joinToString()}"),
            )
        }

        return Result.success(
            ChainProfile(
                vpsIp = values.getValue("VPS_IP"),
                vlessUuid = values.getValue("VLESS_UUID"),
                realityPublicKey = values.getValue("REALITY_PUBKEY"),
                shortId = values.getValue("SHORT_ID"),
                sni = values["SNI"]?.takeIf(String::isNotBlank) ?: ChainProfile.DEFAULT_SNI,
                serverPort = values["SERVER_PORT"]?.toIntOrNull() ?: ChainProfile.DEFAULT_SERVER_PORT,
                localProxyPort = values["LOCAL_PROXY_PORT"]?.toIntOrNull()
                    ?: ChainProfile.DEFAULT_LOCAL_PROXY_PORT,
                entryHop = parseEntryHop(values),
            ),
        )
    }

    /** Present only when all required WireGuard keys are supplied. */
    private fun parseEntryHop(values: Map<String, String>): WireGuardEntry? {
        val hasAll = ENTRY_HOP_KEYS.all { !values[it].isNullOrBlank() }
        if (!hasAll) return null
        return WireGuardEntry(
            privateKey = values.getValue("ENTRY_PRIVKEY"),
            address = values.getValue("ENTRY_ADDRESS"),
            peerPublicKey = values.getValue("ENTRY_PEER_PUBKEY"),
            endpointHost = values.getValue("ENTRY_HOST"),
            endpointPort = values["ENTRY_PORT"]?.toIntOrNull()
                ?: WireGuardEntry.DEFAULT_ENDPOINT_PORT,
            dns = values["ENTRY_DNS"]?.takeIf(String::isNotBlank),
        )
    }

    /**
     * Inverse of [parse] — used to render a QR payload the same parser can read back.
     * Keys already equal to [parse]'s own fallback are omitted: every byte dropped
     * lowers the QR's module count, and a denser code is a harder camera target.
     */
    fun format(profile: ChainProfile): String = buildString {
        appendLine("VPS_IP=${profile.vpsIp}")
        appendLine("VLESS_UUID=${profile.vlessUuid}")
        appendLine("REALITY_PUBKEY=${profile.realityPublicKey}")
        appendLine("SHORT_ID=${profile.shortId}")
        appendUnlessDefault("SNI", profile.sni, ChainProfile.DEFAULT_SNI)
        appendUnlessDefault("SERVER_PORT", profile.serverPort, ChainProfile.DEFAULT_SERVER_PORT)
        appendUnlessDefault(
            "LOCAL_PROXY_PORT",
            profile.localProxyPort,
            ChainProfile.DEFAULT_LOCAL_PROXY_PORT,
        )
        profile.entryHop?.let { entry ->
            appendLine("ENTRY_PRIVKEY=${entry.privateKey}")
            appendLine("ENTRY_ADDRESS=${entry.address}")
            appendLine("ENTRY_PEER_PUBKEY=${entry.peerPublicKey}")
            appendLine("ENTRY_HOST=${entry.endpointHost}")
            appendUnlessDefault(
                "ENTRY_PORT",
                entry.endpointPort,
                WireGuardEntry.DEFAULT_ENDPOINT_PORT,
            )
            entry.dns?.let { appendLine("ENTRY_DNS=$it") }
        }
    }

    private fun <T> StringBuilder.appendUnlessDefault(key: String, value: T, default: T) {
        if (value != default) appendLine("$key=$value")
    }

    private val REQUIRED_KEYS = listOf("VPS_IP", "VLESS_UUID", "REALITY_PUBKEY", "SHORT_ID")
    private val ENTRY_HOP_KEYS = listOf(
        "ENTRY_PRIVKEY",
        "ENTRY_ADDRESS",
        "ENTRY_PEER_PUBKEY",
        "ENTRY_HOST",
    )
}
