package com.verdenroz.vpnchain.core.common.autostart

/**
 * Registers the app to launch when the user logs in.
 *
 * Desktop-only by nature: Android has no login session, and starting at boot
 * there is a `BOOT_COMPLETED` receiver instead. Implementations report
 * [supported] rather than throwing, so the UI can hide a control it cannot honor.
 */
interface LoginAutostart {

    /** False where there is no per-user login-item mechanism we can write. */
    val supported: Boolean

    suspend fun isEnabled(): Boolean

    /** @return failure with a user-showable reason when the entry can't be written. */
    suspend fun setEnabled(enabled: Boolean): Result<Unit>
}

expect fun createLoginAutostart(): LoginAutostart
