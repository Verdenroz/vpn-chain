package com.verdenroz.vpnchain.core.geoip

/** Resolves a hostname to a dotted-decimal IPv4 address; null if it doesn't resolve. */
internal expect suspend fun resolveHostToIp(host: String): String?
