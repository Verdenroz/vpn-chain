package com.verdenroz.vpnchain.core.common.autostart

/**
 * Android has no login session to hook. Starting with the device is a
 * `BOOT_COMPLETED` receiver in the app module, not a login item.
 */
private object UnsupportedLoginAutostart : LoginAutostart {
    override val supported = false
    override suspend fun isEnabled() = false
    override suspend fun setEnabled(enabled: Boolean) = Result.success(Unit)
}

actual fun createLoginAutostart(): LoginAutostart = UnsupportedLoginAutostart
