package com.verdenroz.vpnchain.core.common

/**
 * Resolved once, lazily: the answer cannot change within a session, and
 * touching AWT eagerly would initialise the toolkit just to import this file.
 * Failures count as unavailable — a headless or toolkit-less JVM has no tray
 * either way.
 */
actual val systemTrayAvailable: Boolean by lazy {
    runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
}
