package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.common.PrivateStorage
import java.nio.file.Path

/** Where the engine's control socket and lock live. */
class EnginePaths(val runtimeDir: Path, val configDir: Path) {
    val socket: Path get() = runtimeDir.resolve("control.sock")
    val lock: Path get() = configDir.resolve("engine.lock")

    companion object {
        fun system(): EnginePaths = EnginePaths(
            runtimeDir = Path.of(runtimeBase(System::getenv, System.getProperty("os.name").startsWith("Mac")), "vpn-chain"),
            configDir = PrivateStorage.configDir.toPath(),
        )

        // Linux matches DesktopTunnelController's relay.pid directory. macOS has no XDG_RUNTIME_DIR,
        // and its per-user TMPDIR under /var/folders beats the shared /tmp.
        internal fun runtimeBase(env: (String) -> String?, macOS: Boolean): String =
            env("XDG_RUNTIME_DIR") ?: env("TMPDIR").takeIf { macOS } ?: "/tmp"
    }
}

/**
 * Whether this platform can serve the control socket owner-only. Without POSIX
 * permissions (Windows) the socket would be open to every local user, so the
 * engine does not serve it there.
 */
val controlSocketSupported: Boolean get() = PrivateStorage.posix
