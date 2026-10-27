package com.verdenroz.vpnchain.core.tunnel

import java.io.File

private val PACKAGED_TOOLS = File("/opt/vpn-chain/libexec")

/** The copy of [name] the .deb and Arch packages install, or [name] itself for a PATH lookup in a source build. */
internal fun bundledTool(name: String, dir: File = PACKAGED_TOOLS): String =
    File(dir, name).takeIf { it.canExecute() }?.path ?: name
