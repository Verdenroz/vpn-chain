package com.verdenroz.vpnchain.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot "open the QR scanner" signal from outside the composition (Android
 * widget/deep-link intents). A shared flow rather than a nav argument so the
 * navigation layer doesn't need to thread scanner state through its routes:
 * the app shell navigates to Settings when it sees the request, and
 * [SettingsRoute] consumes it by launching the scanner.
 */
object QrScanRequest {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
