package com.verdenroz.vpnchain.core.common.autostart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Writes a per-user login item: an XDG autostart entry on Linux, a LaunchAgent
 * plist on macOS, and an `HKCU\...\Run` value on Windows. All three are
 * user-scoped on purpose — none of them needs elevation.
 */
internal class DesktopLoginAutostart(
    private val os: DesktopOs = DesktopOs.current(),
    private val homeDir: File = File(System.getProperty("user.home")),
    private val execPath: String? = resolveExecPath(),
) : LoginAutostart {

    // An unpackaged run resolves to the JVM binary; registering that would
    // launch a bare JVM at login, so the control is better shown as unavailable.
    override val supported: Boolean get() = os != DesktopOs.Unknown && execPath != null

    override suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) {
        when (os) {
            DesktopOs.Linux -> linuxEntryFile().isFile
            DesktopOs.Mac -> macAgentFile().isFile
            DesktopOs.Windows -> runReg(AutostartEntries.windowsQueryArgv()) == 0
            DesktopOs.Unknown -> false
        }
    }

    override suspend fun setEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val exec = execPath
            ?: return@withContext Result.failure(
                IllegalStateException("Can only register a packaged build to start at login"),
            )
        runCatching {
            when (os) {
                DesktopOs.Linux -> linuxEntryFile().apply(File::toggleParent)
                    .write(enabled, AutostartEntries.linuxDesktopEntry(exec))

                DesktopOs.Mac -> macAgentFile().apply(File::toggleParent)
                    .write(enabled, AutostartEntries.macLaunchAgentPlist(exec))

                DesktopOs.Windows -> {
                    val argv = if (enabled) {
                        AutostartEntries.windowsRegisterArgv(exec)
                    } else {
                        AutostartEntries.windowsUnregisterArgv()
                    }
                    val code = runReg(argv)
                    // Deleting an absent value is a non-zero "not found", which
                    // is the state the caller asked for anyway.
                    if (code != 0 && enabled) error("reg exited $code")
                }

                DesktopOs.Unknown -> error("Unsupported desktop platform")
            }
        }
    }

    private fun linuxEntryFile(): File {
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(homeDir, ".config")
        return File(configHome, "autostart/vpn-chain.desktop")
    }

    private fun macAgentFile(): File =
        File(homeDir, "Library/LaunchAgents/${AutostartEntries.LABEL}.plist")

    private fun runReg(argv: List<String>): Int =
        ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .let { if (it.waitFor(REG_TIMEOUT_S, TimeUnit.SECONDS)) it.exitValue() else -1 }

    private companion object {
        const val REG_TIMEOUT_S = 10L
    }
}

internal enum class DesktopOs {
    Linux, Mac, Windows, Unknown;

    companion object {
        fun current(): DesktopOs {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.contains("linux") -> Linux
                name.contains("mac") || name.contains("darwin") -> Mac
                name.contains("windows") -> Windows
                else -> Unknown
            }
        }
    }
}

private fun File.toggleParent(): File = also { parentFile?.mkdirs() }

private fun File.write(enabled: Boolean, contents: String) {
    if (enabled) writeText(contents) else delete()
}

private fun resolveExecPath(): String? {
    val command = ProcessHandle.current().info().command().orElse(null) ?: return null
    val name = File(command).name.lowercase()
    return if (name.startsWith("java")) null else command
}

actual fun createLoginAutostart(): LoginAutostart = DesktopLoginAutostart()
