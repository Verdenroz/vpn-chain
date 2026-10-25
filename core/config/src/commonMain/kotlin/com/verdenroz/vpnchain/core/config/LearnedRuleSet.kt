package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.LearnedRoute
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The local rule-set files sing-box reads learned domains from. sing-box
 * watches a local rule-set and reloads it when the file changes, so a write
 * takes effect without restarting the tunnel.
 */
interface LearnedRuleSetStore {
    fun path(route: LearnedRoute): String

    /** Replaces the file in one step, so sing-box never reads a half-written list. */
    fun write(route: LearnedRoute, domains: List<String>)
}

object LearnedRuleSet {
    /** Source format, version 1: the oldest, so every sing-box that loads rule-sets reads it. */
    fun render(domains: List<String>): String = buildJsonObject {
        put("version", 1)
        putJsonArray("rules") {
            addJsonObject { putJsonArray("domain_suffix") { domains.forEach { add(it) } } }
        }
    }.toString()

    fun fileName(route: LearnedRoute): String = when (route) {
        LearnedRoute.Warp -> "learned-warp.json"
        LearnedRoute.RefusedByBoth -> "learned-open.json"
    }
}
