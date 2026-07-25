package com.verdenroz.vpnchain.feature.logs

/** Formats an epoch-millis timestamp as a local wall-clock time (HH:mm:ss). */
expect fun formatLogTime(epochMillis: Long): String
