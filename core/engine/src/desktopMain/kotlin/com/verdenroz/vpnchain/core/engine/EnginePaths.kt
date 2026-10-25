package com.verdenroz.vpnchain.core.engine

import java.nio.file.Path

/** Where the engine's control socket and lock live. */
class EnginePaths(val runtimeDir: Path, val configDir: Path) {
    val socket: Path get() = runtimeDir.resolve("control.sock")
    val lock: Path get() = configDir.resolve("engine.lock")

    companion object {
        fun system(): EnginePaths = EnginePaths(
            // Same directory DesktopTunnelController keeps relay.pid in.
            runtimeDir = Path.of(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp", "vpn-chain"),
            configDir = Path.of(System.getProperty("user.home"), ".config", "vpn-chain"),
        )
    }
}
