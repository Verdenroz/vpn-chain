package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/**
 * Cloudflare WARP credentials for the chain's tail exit — the hop that follows
 * the relay, so the address sites see is Cloudflare's rather than the VPS's.
 * Registered per device against Cloudflare's free API; these are credentials
 * and fall under the same no-logging rule as the chain profile.
 */
@Serializable
data class WarpExit(
    val privateKey: String,
    /** Interface address with prefix, e.g. `172.16.0.2/32`. */
    val addressV4: String,
    val addressV6: String,
    val peerPublicKey: String,
    /**
     * The anycast literal rather than `engage.cloudflareclient.com`: resolving a
     * name here would need DNS before the chain that carries DNS is up.
     */
    val endpointHost: String = DEFAULT_ENDPOINT_HOST,
    val endpointPort: Int = DEFAULT_ENDPOINT_PORT,
    /** When this registration was obtained; drives refresh before Cloudflare's own TTL. */
    val registeredAtMillis: Long = 0L,
) {
    /**
     * Cloudflare gives a free registration about 90 days; refreshing at 60
     * replaces it well before expiry, because a lapsed key fails as a
     * handshake timeout rather than as anything that says "re-register".
     */
    fun isStale(nowMillis: Long): Boolean =
        nowMillis - registeredAtMillis >= REFRESH_AFTER_MILLIS

    companion object {
        const val DEFAULT_ENDPOINT_HOST = "162.159.192.1"
        const val DEFAULT_ENDPOINT_PORT = 2408
        const val REFRESH_AFTER_MILLIS = 60L * 24 * 60 * 60 * 1000
    }
}

/**
 * How much traffic leaves through the WARP tail instead of the relay VPS.
 *
 * The relay's own address is a datacenter IP, and those carry a fraud score
 * that gets whole sites refused (Reddit and ChatGPT both answer 403 on ours).
 * Sending traffic on through WARP changes only the last hop — the relay still
 * carries it, so the VPS never learns less than it did before.
 */
enum class WarpMode {
    /** Exit from the relay VPS, as the chain did before WARP existed. */
    Off,

    /** Only [UserSettings.warpDomains] take the WARP tail. */
    BlockedSites,

    /** Everything exits through WARP. */
    AllTraffic,
}

/**
 * The sites known to refuse the relay's datacenter address — each answers 403
 * through the VPS and 200 through WARP. Only a starting point: it is seeded
 * into settings as an ordinary editable list, not enforced underneath one, so
 * removing an entry here really does stop routing it.
 */
val DEFAULT_WARP_DOMAINS: List<String> = listOf(
    "reddit.com",
    "redd.it",
    "redditmedia.com",
    "redditstatic.com",
    "chatgpt.com",
    "openai.com",
    "oaistatic.com",
    "oaiusercontent.com",
)
