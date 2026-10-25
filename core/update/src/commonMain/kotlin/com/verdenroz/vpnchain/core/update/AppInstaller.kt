package com.verdenroz.vpnchain.core.update

import okio.Path

sealed interface InstallResult {
    /** The platform installed the build or took over. */
    data object Started : InstallResult

    /** The user has not yet let this app install builds; see [AppInstaller.allowInstalls]. */
    data object NeedsPermission : InstallResult

    data class Failed(val message: String) : InstallResult
}

/** Hands a verified build to the platform's installer. */
interface AppInstaller {
    /** The manifest key of the build this device takes, or null where it cannot be updated from here. */
    val artifactKey: String?

    suspend fun install(file: Path, release: AppRelease): InstallResult

    /** Whether [install] would be accepted now rather than answer [InstallResult.NeedsPermission]. */
    fun canInstall(): Boolean = true

    /** Opens the screen where the user lets this app install builds. */
    fun allowInstalls() {}
}
