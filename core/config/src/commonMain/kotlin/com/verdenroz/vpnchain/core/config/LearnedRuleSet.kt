package com.verdenroz.vpnchain.core.config

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Which list a rule-set file carries: sites sent to WARP, sites that fail open
 * through the entry hop, or the sites the user sent to the bare link.
 */
enum class RuleSetKind { Warp, Open, Direct }

/**
 * The local rule-set files sing-box reads learned domains from. sing-box
 * watches a local rule-set and reloads it when the file changes, so a write
 * takes effect without restarting the tunnel.
 */
interface LearnedRuleSetStore {
    fun path(kind: RuleSetKind): String

    /** Replaces the file in one step, so sing-box never reads a half-written list. */
    fun write(kind: RuleSetKind, rules: LearnedRules)
}

/**
 * What one rule-set file matches: [exact] hosts only as themselves, and
 * [suffixes] with every name under them. Only WARP takes suffixes, at a
 * registrable domain; anything leaving the relay is matched exactly.
 */
data class LearnedRules(val exact: List<String> = emptyList(), val suffixes: List<String> = emptyList())

object LearnedRuleSet {
    /** Source format, version 1: the oldest, so every sing-box that loads rule-sets reads it. */
    fun render(rules: LearnedRules): String = buildJsonObject {
        put("version", 1)
        putJsonArray("rules") {
            if (rules.exact.isNotEmpty()) addJsonObject { putJsonArray("domain") { rules.exact.forEach { add(it) } } }
            if (rules.suffixes.isNotEmpty()) addJsonObject { putJsonArray("domain_suffix") { rules.suffixes.forEach { add(it) } } }
        }
    }.toString()

    fun fileName(kind: RuleSetKind): String = when (kind) {
        RuleSetKind.Warp -> "learned-warp.json"
        RuleSetKind.Open -> "learned-open.json"
        RuleSetKind.Direct -> "learned-direct.json"
    }
}
