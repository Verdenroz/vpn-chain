package com.verdenroz.vpnchain.feature.chain

import kotlin.math.abs

/**
 * Readout formatting for session stats.
 *
 * Fixed to one decimal above the kilobyte so the values stop twitching in the
 * last digit while traffic flows — a readout that never settles is harder to
 * read than one that rounds.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < KILO) return "$bytes B"
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= KILO && unitIndex < UNITS.lastIndex) {
        value /= KILO
        unitIndex++
    }
    return "${oneDecimal(value)} ${UNITS[unitIndex]}"
}

internal fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

/**
 * `h:mm:ss` once past an hour, `mm:ss` below it — a leading `0:` on a
 * two-minute session is noise.
 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0)) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    } else {
        "${twoDigits(minutes)}:${twoDigits(seconds)}"
    }
}

private fun twoDigits(value: Long): String = if (value < 10) "0$value" else "$value"

/** Avoids String.format, which commonMain doesn't have. */
private fun oneDecimal(value: Double): String {
    val scaled = (value * 10).toLong()
    return "${scaled / 10}.${abs(scaled % 10)}"
}

private const val KILO = 1_024
private val UNITS = listOf("KB", "MB", "GB", "TB")
