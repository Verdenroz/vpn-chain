package com.verdenroz.vpnchain.core.common

/** This build's version, from the `vpnchain.version` property the launcher passes; empty in a bare dev run. */
val appVersion: String get() = System.getProperty("vpnchain.version").orEmpty()
