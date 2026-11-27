package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal const val VLESS_TAG = "vless-proxy"
internal const val ENTRY_HOP_TAG = "entry-hop"
internal const val WARP_TAG = "warp-exit"
internal const val DEVICE_EXIT_TAG = "device-exit"
internal const val WIREGUARD_MTU = 1280

/**
 * A resolver on the entry peer's internal network — a provider's filtering
 * resolver being the usual case — must be dialed through that peer. Routing
 * it via the relay like the public fallback would make it unreachable, not
 * merely unfiltered.
 */
internal fun JsonObjectBuilder.putDnsServers(
    entry: WireGuardEntry?,
    remoteDetour: String = VLESS_TAG,
    exit: WireGuardEntry? = null,
) {
    val tag = when {
        exit != null -> if (exit.dns != null) "dns-exit" else "dns-remote"
        entry?.dns != null -> "dns-entry"
        else -> "dns-remote"
    }
    putJsonArray("servers") {
        when (tag) {
            "dns-exit" -> addJsonObject {
                put("type", "udp")
                put("tag", tag)
                put("server", exit?.dns)
                put("detour", DEVICE_EXIT_TAG)
            }
            "dns-entry" -> addJsonObject {
                put("type", "udp")
                put("tag", tag)
                put("server", entry?.dns)
                put("detour", ENTRY_HOP_TAG)
            }
            else -> addJsonObject {
                put("type", "https")
                put("tag", tag)
                put("server", "1.1.1.1")
                put("detour", if (exit != null) DEVICE_EXIT_TAG else remoteDetour)
            }
        }
    }
    put("final", tag)
    put("strategy", "ipv4_only")
}

internal fun WireGuardEntry.addresses(): List<String> = address.split(',').map(String::trim)

internal fun JsonArrayBuilder.addWireGuardEndpoint(
    entry: WireGuardEntry,
    allowedIps: List<String> = listOf("0.0.0.0/0"),
) = addJsonObject {
    put("type", "wireguard")
    put("tag", ENTRY_HOP_TAG)
    put("mtu", entry.mtu ?: 1280)
    putJsonArray("address") { entry.addresses().forEach { add(it) } }
    put("private_key", entry.privateKey)
    putJsonArray("peers") {
        addJsonObject {
            put("address", entry.endpointHost)
            put("port", entry.endpointPort)
            put("public_key", entry.peerPublicKey)
            entry.presharedKey?.let { put("pre_shared_key", it) }
            putJsonArray("allowed_ips") { allowedIps.forEach { add(it) } }
            put("persistent_keepalive_interval", entry.keepaliveSeconds ?: 25)
        }
    }
}

/**
 * The WARP tail: a WireGuard peer dialled *through* [detour], so packets
 * leave Cloudflare's network instead of the relay's own datacenter address.
 * Nothing upstream changes — the entry hop and relay still carry the
 * traffic, and Cloudflare sees the relay's address, never the device's.
 */
internal fun JsonArrayBuilder.addWarpEndpoint(warp: WarpExit, detour: String) = addJsonObject {
    put("type", "wireguard")
    put("tag", WARP_TAG)
    put("mtu", 1280)
    putJsonArray("address") { add(warp.addressV4); add(warp.addressV6) }
    put("private_key", warp.privateKey)
    // Without this the handshake is dialled on the bare link, where the kill
    // switch rejects it outright — and where it would bypass the chain anyway.
    put("detour", detour)
    putJsonArray("peers") {
        addJsonObject {
            put("address", warp.endpointHost)
            put("port", warp.endpointPort)
            put("public_key", warp.peerPublicKey)
            putJsonArray("allowed_ips") { add("0.0.0.0/0"); add("::/0") }
            put("persistent_keepalive_interval", 25)
        }
    }
}

/**
 * This device's own exit: a WireGuard peer dialled through [detour], so the
 * traffic leaves the relay for the peer and the peer's network sees the relay's
 * address, never the device's.
 */
internal fun JsonArrayBuilder.addDeviceExitEndpoint(exit: WireGuardEntry, detour: String) = addJsonObject {
    put("type", "wireguard")
    put("tag", DEVICE_EXIT_TAG)
    put("mtu", minOf(exit.mtu ?: WIREGUARD_MTU, WIREGUARD_MTU))
    putJsonArray("address") { exit.addresses().forEach { add(it) } }
    put("private_key", exit.privateKey)
    put("detour", detour)
    putJsonArray("peers") {
        addJsonObject {
            put("address", exit.endpointHost)
            put("port", exit.endpointPort)
            put("public_key", exit.peerPublicKey)
            exit.presharedKey?.let { put("pre_shared_key", it) }
            putJsonArray("allowed_ips") {
                add("0.0.0.0/0")
                if (exit.addresses().any { ':' in it }) add("::/0")
            }
            put("persistent_keepalive_interval", exit.keepaliveSeconds ?: 25)
        }
    }
}
