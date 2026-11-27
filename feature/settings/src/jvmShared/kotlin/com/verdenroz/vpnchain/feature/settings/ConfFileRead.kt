package com.verdenroz.vpnchain.feature.settings

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val CONF_MAX_BYTES = 64 * 1024

private const val BYTE_ORDER_MARK = '﻿'

/**
 * Reads a chosen file as a conf. A real one is a few hundred bytes, so anything
 * past [CONF_MAX_BYTES] is not read further.
 */
internal fun readPickedConf(open: () -> InputStream?): PickedConf = try {
    val bytes = open()?.use { it.readNBytes(CONF_MAX_BYTES + 1) }
    when {
        bytes == null -> PickedConf.Unreadable
        bytes.size > CONF_MAX_BYTES -> PickedConf.TooLarge
        else -> PickedConf.Text(decodeUtf8(bytes).removePrefix(BYTE_ORDER_MARK.toString()))
    }
} catch (_: IOException) {
    PickedConf.Unreadable
} catch (_: SecurityException) {
    PickedConf.Unreadable
}

private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()
