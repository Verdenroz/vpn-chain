package com.verdenroz.vpnchain.feature.logs

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

actual fun formatLogTime(epochMillis: Long): String =
    formatter.format(Instant.ofEpochMilli(epochMillis))
