package com.verdenroz.vpnchain.core.common.autostart

/**
 * Android has no login session, so the equivalent is starting with the device.
 *
 * There is nothing to register: the persisted preference *is* the switch, and
 * the app's `BOOT_COMPLETED` receiver reads it. Reported as supported so the
 * settings row appears — the capability exists here, just by another mechanism.
 */
private object BootLoginAutostart : LoginAutostart {
    override val supported = true
    override suspend fun isEnabled() = false
    override suspend fun setEnabled(enabled: Boolean) = Result.success(Unit)
}

actual fun createLoginAutostart(): LoginAutostart = BootLoginAutostart
