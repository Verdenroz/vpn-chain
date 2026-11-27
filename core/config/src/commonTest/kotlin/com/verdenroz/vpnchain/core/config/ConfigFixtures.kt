package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun profile() = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    serverPort = 443,
    localProxyPort = 1080,
)

internal fun entry(dns: String? = null) = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "198.51.100.20",
    dns = dns,
)

internal fun warp() = WarpExit(
    privateKey = "warp-priv",
    addressV4 = "172.16.0.2/32",
    addressV6 = "2606:4700::2/128",
    peerPublicKey = "warp-peer",
)

internal fun probe() = ProbeProxy(relayPort = 41001, warpPort = 41002, fallbackPort = 41003, username = "u", password = "p")

internal fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

internal fun JsonArray.taggedWith(tag: String): JsonObject? =
    map { it.jsonObject }.firstOrNull { it["tag"]?.jsonPrimitive?.content == tag }

internal fun deviceExit(dns: String? = null) = WireGuardEntry(
    privateKey = "exit-priv",
    address = "10.5.0.2/32",
    peerPublicKey = "exit-peer",
    endpointHost = "198.51.100.30",
    dns = dns,
)
