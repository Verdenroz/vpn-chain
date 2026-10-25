package com.verdenroz.vpnchain.core.update

enum class UpdateFailure { NoBuild, Network, Corrupt, Install }

sealed interface AppUpdateState {
    data object UpToDate : AppUpdateState

    data class Available(val release: AppRelease) : AppUpdateState

    /** [progress] is the fraction received, 0 to 1. */
    data class Downloading(val release: AppRelease, val progress: Float) : AppUpdateState

    /** The verified build is with the platform installer, which is waiting on the user or the system. */
    data class Installing(val release: AppRelease) : AppUpdateState

    /** The platform will not install builds for this app until the user allows it. */
    data class NeedsPermission(val release: AppRelease) : AppUpdateState

    /** The platform installed the build and the app is yet to restart into it. */
    data class Ready(val release: AppRelease) : AppUpdateState

    /** [release] is what a retry would fetch; [detail] is the installer's own message, if it gave one. */
    data class Failed(val release: AppRelease, val failure: UpdateFailure, val detail: String? = null) : AppUpdateState
}

fun AppUpdateState.release(): AppRelease? =
    when (this) {
        AppUpdateState.UpToDate -> null
        is AppUpdateState.Available -> release
        is AppUpdateState.Downloading -> release
        is AppUpdateState.Installing -> release
        is AppUpdateState.NeedsPermission -> release
        is AppUpdateState.Ready -> release
        is AppUpdateState.Failed -> release
    }

/** How the last look for a release went, for the one place that says so. */
enum class CheckStatus { Idle, Checking, Failed, Done }
