package com.verdenroz.vpnchain.core.warp

import com.verdenroz.vpnchain.core.model.WarpExit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What registration returns, reduced to the fields the tunnel actually needs. */
internal data class WarpRegistrationResult(
    val peerPublicKey: String,
    val addressV4: String,
    val addressV6: String,
) {
    fun toExit(privateKey: String, nowMillis: Long) = WarpExit(
        privateKey = privateKey,
        // Cloudflare hands back bare addresses; WireGuard wants host prefixes,
        // and anything wider would claim to route traffic that isn't ours.
        addressV4 = "$addressV4/32",
        addressV6 = "$addressV6/128",
        peerPublicKey = peerPublicKey,
        registeredAtMillis = nowMillis,
    )
}

/**
 * Pure request and response handling for Cloudflare's registration API,
 * kept apart from the transport so its shape can be tested offline.
 */
internal object WarpRegistration {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A fixed acceptance timestamp rather than the current time: the API only
     * checks that one is present, and formatting a date would drag a
     * date-time dependency into common code for a field nothing reads back.
     */
    fun requestBody(publicKey: String): String = buildJsonObject {
        put("key", publicKey)
        put("install_id", "")
        put("fcm_token", "")
        put("tos", TOS_TIMESTAMP)
        put("model", "PC")
        put("type", "Android")
        put("locale", "en_US")
    }.toString()

    /** @return null when the body is missing anything the tunnel needs. */
    fun parse(body: String): WarpRegistrationResult? = runCatching {
        val response = json.decodeFromString<RegistrationResponse>(body)
        val peer = response.config.peers.firstOrNull() ?: return null
        val addresses = response.config.iface.addresses
        WarpRegistrationResult(
            peerPublicKey = peer.publicKey,
            addressV4 = addresses.v4,
            addressV6 = addresses.v6,
        ).takeIf {
            it.peerPublicKey.isNotBlank() && it.addressV4.isNotBlank() && it.addressV6.isNotBlank()
        }
    }.getOrNull()

    private const val TOS_TIMESTAMP = "2024-01-01T00:00:00.000Z"

    @Serializable
    private data class RegistrationResponse(val config: Config)

    @Serializable
    private data class Config(
        val peers: List<Peer>,
        // `interface` is a hard keyword, so the field can't carry the wire name.
        @SerialName("interface") val iface: Interface,
    )

    @Serializable
    private data class Peer(@SerialName("public_key") val publicKey: String)

    @Serializable
    private data class Interface(val addresses: Addresses)

    @Serializable
    private data class Addresses(val v4: String, val v6: String)
}
