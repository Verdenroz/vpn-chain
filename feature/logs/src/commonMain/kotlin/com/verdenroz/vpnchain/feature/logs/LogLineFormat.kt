package com.verdenroz.vpnchain.feature.logs

/** A log line split into the parts the panel prints in separate columns. */
internal data class ParsedLine(val level: String?, val body: String)

/**
 * sing-box stamps every line with its own timezone and date, which the panel
 * already shows in its own column — strip it rather than print the time twice.
 * Lines the app wrote itself carry no level and are left intact.
 */
internal fun parseLogLine(raw: String): ParsedLine {
    val withoutStamp = SINGBOX_STAMP.replace(raw, "")
    val level = LEVEL.find(withoutStamp)?.groupValues?.get(1)
        ?: return ParsedLine(level = null, body = withoutStamp)
    return ParsedLine(level = level, body = withoutStamp.removePrefix(level).trimStart())
}

private val SINGBOX_STAMP = Regex("""^[+-]\d{4}\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\s+""")
private val LEVEL = Regex("""^(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|PANIC)\s""")
