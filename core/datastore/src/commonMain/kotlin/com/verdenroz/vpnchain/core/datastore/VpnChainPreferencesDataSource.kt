package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Preference-backed storage for the chain profile and app settings.
 * Lives in app-private storage; profile fields are credentials, so nothing
 * here may ever be logged or exported.
 */
class VpnChainPreferencesDataSource(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<SavedProfile>> = dataStore.data.map(::readProfiles)

    /** The selected profile, or the first one when the selection is stale. */
    val profile: Flow<SavedProfile?> = dataStore.data.map { prefs ->
        val saved = readProfiles(prefs)
        val activeId = prefs[ACTIVE_PROFILE_ID_KEY]
        saved.firstOrNull { it.id == activeId } ?: saved.firstOrNull()
    }

    val activeProfileId: Flow<String?> = dataStore.data.map { prefs ->
        val saved = readProfiles(prefs)
        val activeId = prefs[ACTIVE_PROFILE_ID_KEY]
        (saved.firstOrNull { it.id == activeId } ?: saved.firstOrNull())?.id
    }

    /**
     * Migration is read-time and non-destructive: an install predating the
     * profile list is surfaced as its single profile, and the legacy key is
     * only retired once something writes the new shape. An upgrade that goes
     * wrong therefore looks like nothing happened, not like a wiped config.
     */
    private fun readProfiles(prefs: Preferences): List<SavedProfile> {
        storedEntries(prefs)?.let { entries -> return entries.mapNotNull(::decodeEntry) }
        return listOfNotNull(prefs[PROFILE_KEY]?.let(::decodeLegacyProfile))
    }

    private fun storedEntries(prefs: Preferences): JsonArray? =
        prefs[PROFILES_KEY]?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() }

    /** One entry that fails to decode must not take the rest of the list with it. */
    private fun decodeEntry(entry: JsonElement): SavedProfile? =
        runCatching { json.decodeFromJsonElement<SavedProfile>(upgradeProfileShape(entry)) }.getOrNull()

    private fun decodeLegacyProfile(raw: String): SavedProfile? {
        val profile = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val name = profile["vpsIp"] ?: return null
        val wrapped = JsonObject(
            mapOf(
                "id" to JsonPrimitive(LEGACY_PROFILE_ID),
                "name" to name,
                "profile" to profile,
            ),
        )
        return decodeEntry(wrapped)
    }

    /**
     * Profiles stored before connections could be WireGuard-only nest the relay
     * under `profile`, with the entry hop inside it.
     */
    private fun upgradeProfileShape(element: JsonElement): JsonElement {
        val saved = element.jsonObject
        val profile = saved["profile"]?.jsonObject ?: return element
        val entry = profile["entryHop"]?.takeIf { it !is JsonNull }
        return JsonObject(
            saved - "profile" + ("relay" to JsonObject(profile - "entryHop")) +
                listOfNotNull(entry?.let { "wireGuard" to it }),
        )
    }

    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            themeConfig = prefs[THEME_KEY]
                ?.let { raw -> runCatching { ThemeConfig.valueOf(raw) }.getOrNull() }
                ?: ThemeConfig.FOLLOW_SYSTEM,
            systemWideTun = prefs[SYSTEM_WIDE_TUN_KEY] ?: true,
            killSwitchEnabled = prefs[KILL_SWITCH_ENABLED_KEY] ?: true,
            allowLocalNetworks = prefs[ALLOW_LOCAL_NETWORKS_KEY] ?: true,
            dnsFilter = prefs[DNS_FILTER_KEY]
                ?.let { raw -> runCatching { DnsFilter.valueOf(raw) }.getOrNull() }
                ?: DnsFilter.AdsAndTrackers,
            entryHopEnabled = prefs[ENTRY_HOP_ENABLED_KEY] ?: true,
            warpMode = prefs[WARP_MODE_KEY]
                ?.let { raw -> runCatching { WarpMode.valueOf(raw) }.getOrNull() }
                ?: WarpMode.Automatic,
            learnBlockedSites = prefs[LEARN_BLOCKED_SITES_KEY] ?: true,
            failOpen = prefs[FAIL_OPEN_KEY] ?: true,
            autoConnectOnLaunch = prefs[AUTO_CONNECT_ON_LAUNCH_KEY] ?: false,
            autoReconnect = prefs[AUTO_RECONNECT_KEY] ?: true,
            autoStartOnLogin = prefs[AUTO_START_ON_LOGIN_KEY] ?: false,
            closeToTray = prefs[CLOSE_TO_TRAY_KEY] ?: true,
        )
    }

    /**
     * Whether the user last asked to be connected. Survives restarts so a
     * relaunch or a reboot can tell "they turned it off" apart from "it died",
     * which is the difference between resuming and staying down.
     */
    val connectionIntent: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[CONNECTION_INTENT_KEY] ?: false }

    suspend fun setConnectionIntent(wanted: Boolean) {
        dataStore.edit { it[CONNECTION_INTENT_KEY] = wanted }
    }

    /** Last public IP observed while untunnelled. Sensitive (it is the user's
     *  real address) and subject to the same no-logging rule as the profile. */
    val lastOriginIp: Flow<String?> = dataStore.data.map { prefs -> prefs[LAST_ORIGIN_IP_KEY] }

    suspend fun setLastOriginIp(ip: String) {
        dataStore.edit { it[LAST_ORIGIN_IP_KEY] = ip }
    }

    suspend fun setProfiles(profiles: List<SavedProfile>) {
        dataStore.edit { prefs ->
            // Entries this build cannot read are written back untouched, so a
            // save never discards a connection a newer shape stored.
            val unreadable = storedEntries(prefs).orEmpty().filter { decodeEntry(it) == null }
            prefs[PROFILES_KEY] = JsonArray(json.encodeToJsonElement(profiles).jsonArray + unreadable).toString()
            // The single-slot key has been folded into the list by now; leaving
            // it would resurrect a stale profile if the list ever failed to parse.
            prefs.remove(PROFILE_KEY)
        }
    }

    suspend fun setActiveProfileId(id: String) {
        dataStore.edit { it[ACTIVE_PROFILE_ID_KEY] = id }
    }

    suspend fun clearProfile() {
        dataStore.edit {
            it.remove(PROFILE_KEY)
            it.remove(PROFILES_KEY)
            it.remove(ACTIVE_PROFILE_ID_KEY)
        }
    }

    suspend fun setThemeConfig(themeConfig: ThemeConfig) {
        dataStore.edit { it[THEME_KEY] = themeConfig.name }
    }

    suspend fun setSystemWideTun(enabled: Boolean) {
        dataStore.edit { it[SYSTEM_WIDE_TUN_KEY] = enabled }
    }

    suspend fun setKillSwitchEnabled(enabled: Boolean) {
        dataStore.edit { it[KILL_SWITCH_ENABLED_KEY] = enabled }
    }

    suspend fun setAllowLocalNetworks(enabled: Boolean) {
        dataStore.edit { it[ALLOW_LOCAL_NETWORKS_KEY] = enabled }
    }

    suspend fun setDnsFilter(filter: DnsFilter) {
        dataStore.edit { it[DNS_FILTER_KEY] = filter.name }
    }

    suspend fun setEntryHopEnabled(enabled: Boolean) {
        dataStore.edit { it[ENTRY_HOP_ENABLED_KEY] = enabled }
    }

    suspend fun setWarpMode(mode: WarpMode) {
        dataStore.edit { it[WARP_MODE_KEY] = mode.name }
    }

    suspend fun setLearnBlockedSites(enabled: Boolean) {
        dataStore.edit { it[LEARN_BLOCKED_SITES_KEY] = enabled }
    }

    suspend fun setFailOpen(enabled: Boolean) {
        dataStore.edit { it[FAIL_OPEN_KEY] = enabled }
    }

    val learnedSites: Flow<List<LearnedSite>> = dataStore.data.map(::readLearned)

    /** Domains whose learning is paused, with the time it resumes. */
    val learnedSuppressions: Flow<Map<String, Long>> = dataStore.data.map(::readSuppressed)

    /** Replaces any earlier entry for the same domain. */
    suspend fun putLearnedSite(site: LearnedSite) {
        dataStore.edit { prefs ->
            val kept = readLearned(prefs).filterNot { it.domain == site.domain }
            prefs[LEARNED_SITES_KEY] = json.encodeToString(kept + site)
        }
    }

    /** Drops the entry and stops it being learned again until [suppressUntilMillis]. */
    suspend fun removeLearnedSite(domain: String, suppressUntilMillis: Long) {
        dataStore.edit { prefs ->
            prefs[LEARNED_SITES_KEY] = json.encodeToString(readLearned(prefs).filterNot { it.domain == domain })
            prefs[LEARNED_SUPPRESSED_KEY] =
                json.encodeToString(readSuppressed(prefs) + (domain to suppressUntilMillis))
        }
    }

    private fun readLearned(prefs: Preferences): List<LearnedSite> = prefs[LEARNED_SITES_KEY]
        ?.let { raw -> runCatching { json.decodeFromString<List<LearnedSite>>(raw) }.getOrNull() }
        .orEmpty()

    /** Domains found reachable on the relay, with the time that verdict lapses. */
    val reachableDomains: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[REACHABLE_DOMAINS_KEY]
            ?.let { raw -> runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrNull() }
            .orEmpty()
    }

    suspend fun setReachableDomains(domains: Map<String, Long>) {
        dataStore.edit { it[REACHABLE_DOMAINS_KEY] = json.encodeToString(domains) }
    }

    private fun readSuppressed(prefs: Preferences): Map<String, Long> = prefs[LEARNED_SUPPRESSED_KEY]
        ?.let { raw -> runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrNull() }
        .orEmpty()

    /**
     * The registered WARP tail, or null before one exists. A private key like
     * any other in here: never logged, never exported with the profile.
     */
    val warpExit: Flow<WarpExit?> = dataStore.data.map { prefs ->
        prefs[WARP_EXIT_KEY]?.let { raw ->
            runCatching { json.decodeFromString<WarpExit>(raw) }.getOrNull()
        }
    }

    suspend fun setWarpExit(exit: WarpExit) {
        dataStore.edit { it[WARP_EXIT_KEY] = json.encodeToString(exit) }
    }

    suspend fun setAutoConnectOnLaunch(enabled: Boolean) {
        dataStore.edit { it[AUTO_CONNECT_ON_LAUNCH_KEY] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        dataStore.edit { it[AUTO_RECONNECT_KEY] = enabled }
    }

    suspend fun setAutoStartOnLogin(enabled: Boolean) {
        dataStore.edit { it[AUTO_START_ON_LOGIN_KEY] = enabled }
    }

    suspend fun setCloseToTray(enabled: Boolean) {
        dataStore.edit { it[CLOSE_TO_TRAY_KEY] = enabled }
    }

    private companion object {
        val PROFILE_KEY = stringPreferencesKey("chain_profile_json")
        val PROFILES_KEY = stringPreferencesKey("chain_profiles_json")
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        const val LEGACY_PROFILE_ID = "legacy"
        val THEME_KEY = stringPreferencesKey("theme_config")
        val SYSTEM_WIDE_TUN_KEY = booleanPreferencesKey("system_wide_tun")
        val KILL_SWITCH_ENABLED_KEY = booleanPreferencesKey("kill_switch_enabled")
        val ALLOW_LOCAL_NETWORKS_KEY = booleanPreferencesKey("allow_local_networks")
        val DNS_FILTER_KEY = stringPreferencesKey("dns_filter")
        val ENTRY_HOP_ENABLED_KEY = booleanPreferencesKey("entry_hop_enabled")
        val WARP_MODE_KEY = stringPreferencesKey("warp_mode")
        val LEARN_BLOCKED_SITES_KEY = booleanPreferencesKey("learn_blocked_sites")
        val FAIL_OPEN_KEY = booleanPreferencesKey("fail_open")
        val LEARNED_SITES_KEY = stringPreferencesKey("learned_sites_json")
        val REACHABLE_DOMAINS_KEY = stringPreferencesKey("reachable_domains_json")
        val LEARNED_SUPPRESSED_KEY = stringPreferencesKey("learned_suppressed_json")
        val WARP_EXIT_KEY = stringPreferencesKey("warp_exit_json")
        val LAST_ORIGIN_IP_KEY = stringPreferencesKey("last_origin_ip")
        val AUTO_CONNECT_ON_LAUNCH_KEY = booleanPreferencesKey("auto_connect_on_launch")
        val AUTO_RECONNECT_KEY = booleanPreferencesKey("auto_reconnect")
        val CONNECTION_INTENT_KEY = booleanPreferencesKey("connection_intent")
        val AUTO_START_ON_LOGIN_KEY = booleanPreferencesKey("auto_start_on_login")
        val CLOSE_TO_TRAY_KEY = booleanPreferencesKey("close_to_tray")
    }
}
