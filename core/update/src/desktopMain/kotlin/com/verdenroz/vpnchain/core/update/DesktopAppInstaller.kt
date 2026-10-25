package com.verdenroz.vpnchain.core.update

import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path as OkioPath

/**
 * Swaps a verified tarball into the managed install that `cli/install-desktop.sh` sets up, then restarts into it.
 * The relay is a separate process, so it keeps running across the restart for the new window to adopt.
 */
class DesktopAppInstaller(
    private val installation: DesktopInstall,
    appPath: String? = System.getProperty("jpackage.app-path"),
    private val relaunch: (Path) -> Unit = ::relaunchAndExit,
) : AppInstaller {
    /** Only the managed install can be replaced, so any other copy is never offered an update. */
    override val artifactKey: String? =
        "linux-x64".takeIf {
            System.getProperty("os.name").startsWith("Linux") &&
                System.getProperty("os.arch") in setOf("amd64", "x86_64") &&
                installation.manages(appPath)
        }

    override suspend fun install(file: OkioPath, release: AppRelease): InstallResult {
        try {
            withContext(Dispatchers.IO) { installation.install(file.toNioPath(), release.versionName) }
        } catch (e: IOException) {
            return InstallResult.Failed(e.message ?: "Could not install the update")
        }
        relaunch(installation.launcher)
        return InstallResult.Started
    }
}

// The jpackage launcher treats a process carrying this as its own re-exec and skips the JVM arguments.
private const val JPACKAGE_LAUNCHER_ENV = "_JPACKAGE_LAUNCHER"

// Waits for this process to exit first so the preferences file is never open in two processes.
internal fun relaunchCommand(
    pid: Long,
    launcher: Path,
    parentEnv: Map<String, String> = System.getenv(),
): ProcessBuilder {
    val wait = "while kill -0 \"\$1\" 2>/dev/null; do sleep 0.2; done; exec \"\$2\""
    return ProcessBuilder("sh", "-c", wait, "sh", "$pid", launcher.toString()).inheritIO().apply {
        environment().apply {
            clear()
            putAll(parentEnv - JPACKAGE_LAUNCHER_ENV)
        }
    }
}

private fun relaunchAndExit(launcher: Path) {
    relaunchCommand(ProcessHandle.current().pid(), launcher).start()
    exitProcess(0)
}
