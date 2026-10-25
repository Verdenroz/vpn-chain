package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import java.io.File
import java.io.RandomAccessFile

/**
 * Whether an adopted config was rendered by this build's schema. An older one
 * can send sites past the relay that this build would not, so it is never reused.
 */
internal fun renderedByThisBuild(stamp: String?): Boolean =
    stamp?.trim() == SingBoxConfigFactory.RENDER_SCHEMA.toString()

/** What [log] gained after [offset]: the output of one launch, without an earlier run's lines. */
internal fun logSince(log: File, offset: Long): String = runCatching {
    RandomAccessFile(log, "r").use { file ->
        if (file.length() <= offset) return@use ""
        file.seek(offset)
        ByteArray((file.length() - offset).toInt()).also(file::readFully).decodeToString()
    }
}.getOrDefault("")
