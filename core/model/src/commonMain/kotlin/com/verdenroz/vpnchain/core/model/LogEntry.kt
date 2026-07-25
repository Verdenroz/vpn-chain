package com.verdenroz.vpnchain.core.model

/** One line emitted by the tunnel core (sing-box output or controller events). */
data class LogEntry(
    val timestampMillis: Long,
    val message: String,
)
