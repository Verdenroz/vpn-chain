package com.verdenroz.vpnchain.core.warp.probe

import java.net.IDN

internal actual fun loadBundledPublicSuffixRules(): Sequence<String> {
    val stream = checkNotNull(PublicSuffixList::class.java.getResourceAsStream("/vpnchain/public_suffix_list.dat")) {
        "public_suffix_list.dat is missing from the build"
    }
    // Hosts arrive in their ASCII (punycode) form, and the list writes IDN rules in Unicode.
    return stream.bufferedReader().use { reader ->
        reader.readLines().map { line ->
            if (line.all { it.code < 0x80 }) line else runCatching { IDN.toASCII(line.trim()) }.getOrDefault(line)
        }
    }.asSequence()
}
