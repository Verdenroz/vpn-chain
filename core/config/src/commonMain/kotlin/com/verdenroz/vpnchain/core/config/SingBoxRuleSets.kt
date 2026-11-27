package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.DnsFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Upstream filtering's job, done client-side: reject the lookup rather than
 * answer with an address. `refused` fails callers fast instead of leaving
 * them to time out on a black-holed connection. One rule covers every
 * rule-set of the level — a name is refused if any of them lists it.
 */
internal fun JsonObjectBuilder.putBlocklistRules(filter: DnsFilter) {
    putJsonArray("rules") {
        addJsonObject {
            putJsonArray("rule_set") { blocklistsFor(filter).forEach { add(it.tag) } }
            put("action", "reject")
            put("method", "default")
        }
    }
}

internal fun JsonObjectBuilder.putRuleSets(
    filter: DnsFilter,
    learnedWarpPath: String?,
    learnedOpenPath: String?,
    learnedDirectPath: String?,
    detour: String = VLESS_TAG,
) {
    val blocklists = blocklistsFor(filter)
    if (blocklists.isEmpty() && learnedWarpPath == null && learnedOpenPath == null && learnedDirectPath == null) return
    putJsonArray("rule_set") {
        blocklists.forEach { addBlocklistRuleSet(it, detour) }
        learnedWarpPath?.let { addLearnedRuleSet(LEARNED_WARP_TAG, it) }
        learnedOpenPath?.let { addLearnedRuleSet(LEARNED_OPEN_TAG, it) }
        learnedDirectPath?.let { addLearnedRuleSet(LEARNED_DIRECT_TAG, it) }
    }
}

/** Whether a rendered config routes by a learned rule-set; false for one that cannot be read. */
internal fun configRoutesLearnedSites(configJson: String): Boolean = runCatching {
    Json.parseToJsonElement(configJson).jsonObject["route"]?.jsonObject?.get("rule_set")?.jsonArray.orEmpty()
        .any { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull in setOf(LEARNED_WARP_TAG, LEARNED_OPEN_TAG, LEARNED_DIRECT_TAG) }
}.getOrDefault(false)

internal fun JsonArrayBuilder.addLearnedRuleSet(tag: String, path: String) = addJsonObject {
    put("type", "local")
    put("tag", tag)
    put("format", "source")
    put("path", path)
}

/**
 * Downloaded through the chain, never around it — a blocklist fetched over
 * the bare link would announce the app to the network it is hiding from.
 * Binary `.srs` is the compiled form sing-box loads directly.
 */
internal fun JsonArrayBuilder.addBlocklistRuleSet(list: Blocklist, detour: String) = addJsonObject {
    put("type", "remote")
    put("tag", list.tag)
    put("format", "binary")
    put("url", list.url)
    put("download_detour", detour)
    put("update_interval", "168h")
}

internal fun blocklistsFor(filter: DnsFilter): List<Blocklist> = when (filter) {
    DnsFilter.Off -> emptyList()
    DnsFilter.Malware -> listOf(THREATS_LIST)
    DnsFilter.AdsAndTrackers -> listOf(THREATS_LIST, GEOSITE_ADS_LIST, ADS_TRACKERS_LIST)
}

internal const val LEARNED_WARP_TAG = "learned-warp"
internal const val LEARNED_OPEN_TAG = "learned-open"
internal const val LEARNED_DIRECT_TAG = "learned-direct"

internal data class Blocklist(val tag: String, val url: String)

/**
 * These files decide what the chain refuses to resolve, so each comes from
 * a party we already trust: sing-box's own geosite mirror (the upstream
 * that ships the binary we run), and our own `blocklists` release, where
 * CI converts hagezi's lists with a pinned sing-box — no third-party
 * conversion repo in between. See `.github/workflows/blocklists.yml`.
 */
internal const val BLOCKLISTS_RELEASE =
    "https://github.com/Verdenroz/vpn-chain/releases/download/blocklists"

/** hagezi TIF medium: malware, phishing, and scam domains. */
internal val THREATS_LIST = Blocklist("blocklist-threats", "$BLOCKLISTS_RELEASE/threats.srs")

/** hagezi Pro++: ads, trackers, telemetry. 99% on d3ward's adblock test. */
internal val ADS_TRACKERS_LIST =
    Blocklist("blocklist-ads-trackers", "$BLOCKLISTS_RELEASE/ads-trackers.srs")

/**
 * v2fly's ad list, kept alongside hagezi's: it blocks apex domains hagezi
 * deliberately spares (e.g. `doubleclick.net`), and it costs 8 KB.
 */
internal val GEOSITE_ADS_LIST = Blocklist(
    "blocklist-ads",
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
)
