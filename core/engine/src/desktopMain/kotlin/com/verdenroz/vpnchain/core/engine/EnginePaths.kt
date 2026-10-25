package com.verdenroz.vpnchain.core.engine

import java.nio.file.Path

/** Where the engine's control socket and lock live. */
class EnginePaths(val runtimeDir: Path, val configDir: Path) {
    val socket: Path get() = runtimeDir.resolve("control.sock")
    val lock: Path get() = configDir.resolve("engine.lock")

    companion object {
        fun system(env: (String) -> String? = System::getenv): EnginePaths = EnginePaths(
            runtimeDir = Path.of(
                env("XDG_RUNTIME_DIR")?.takeIf(String::isNotEmpty) ?: System.getProperty("java.io.tmpdir"),
                "vpn-chain",
            ),
            configDir = Path.of(System.getProperty("user.home"), ".config", "vpn-chain"),
        )
    }
}
