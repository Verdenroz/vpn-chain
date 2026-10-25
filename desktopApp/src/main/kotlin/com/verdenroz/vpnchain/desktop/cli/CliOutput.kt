package com.verdenroz.vpnchain.desktop.cli

import com.verdenroz.vpnchain.core.engine.EngineStatus
import com.verdenroz.vpnchain.core.engine.LogLine
import com.verdenroz.vpnchain.core.engine.ProfileSummary
import com.verdenroz.vpnchain.core.model.ConnectionKind
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val TunnelState.label: String
    get() = when (this) {
        TunnelState.Disconnected -> "disconnected"
        TunnelState.Connecting -> "connecting"
        TunnelState.Connected -> "connected"
        TunnelState.Error -> "error"
    }

internal val KillSwitchState.label: String
    get() = when (this) {
        KillSwitchState.Active -> "active"
        KillSwitchState.Disabled -> "off"
        KillSwitchState.HelperUnavailable -> "helper-unavailable"
        KillSwitchState.ExternalAppRequired -> "external-app-required"
    }

internal val ConnectionKind.label: String
    get() = when (this) {
        ConnectionKind.Chain -> "chain"
        ConnectionKind.Relay -> "relay"
        ConnectionKind.WireGuard -> "wireguard"
    }

/** [detail] is the status detail already resolved to text. */
internal fun statusText(status: EngineStatus, detail: String?): String = buildList {
    add("state" to status.state.label)
    add("connection" to (status.profile?.let { "${it.name} (${it.kind.label})" } ?: "none"))
    add("entry hop" to if (status.entryHopActive) "up" else "down")
    add("kill switch" to status.killSwitch.label)
    status.exitIp?.let { add("exit ip" to it) }
    detail?.let { add("detail" to it) }
}.joinToString("\n") { (key, value) -> "${key.padEnd(12)}$value" }

internal fun statusJson(status: EngineStatus, detail: String?): JsonObject = buildJsonObject {
    put("state", status.state.label)
    put("connection", status.profile?.let(::profileJson) ?: JsonNull)
    put("entryHop", status.entryHopActive)
    put("killSwitch", status.killSwitch.label)
    put("exitIp", status.exitIp)
    put("detail", detail)
}

internal fun profilesText(profiles: List<ProfileSummary>): String =
    if (profiles.isEmpty()) {
        "no saved connections"
    } else {
        val width = profiles.maxOf { it.name.length }
        profiles.joinToString("\n") {
            "${if (it.active) "*" else " "} ${it.name.padEnd(width)}  ${it.kind.label.padEnd(9)}  ${it.id}"
        }
    }

internal fun profilesJson(profiles: List<ProfileSummary>): JsonArray = buildJsonArray {
    profiles.forEach { add(profileJson(it)) }
}

internal fun logsText(lines: List<LogLine>): String = lines.joinToString("\n") { it.message }

internal fun logsJson(lines: List<LogLine>): JsonArray = buildJsonArray {
    lines.forEach { line ->
        add(
            buildJsonObject {
                put("timestampMillis", line.timestampMillis)
                put("message", line.message)
            },
        )
    }
}

private fun profileJson(profile: ProfileSummary) = buildJsonObject {
    put("id", profile.id)
    put("name", profile.name)
    put("kind", profile.kind.label)
    put("active", profile.active)
}
