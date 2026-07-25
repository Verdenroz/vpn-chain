package com.verdenroz.vpnchain.feature.logs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

actual fun formatLogTime(epochMillis: Long): String = formatter.format(Date(epochMillis))
