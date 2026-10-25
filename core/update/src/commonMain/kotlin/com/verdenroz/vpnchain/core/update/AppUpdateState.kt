package com.verdenroz.vpnchain.core.update

enum class UpdateFailure { NoBuild, Network, Corrupt, Install }

sealed interface AppUpdateState {
    /** What the state is about, or null when there is no newer release. */
    val release: AppRelease?

    data object UpToDate : AppUpdateState {
        override val release: AppRelease? = null
    }

    data class Available(override val release: AppRelease) : AppUpdateState

    /** [progress] is the fraction received, 0 to 1. */
    data class Downloading(override val release: AppRelease, val progress: Float) : AppUpdateState

    /** The verified build is with the platform installer, which is waiting on the user or the system. */
    data class Installing(override val release: AppRelease) : AppUpdateState

    /** The platform will not install builds for this app until the user allows it. */
    data class NeedsPermission(override val release: AppRelease) : AppUpdateState

    /** The platform installed the build and the app is yet to restart into it. */
    data class Ready(override val release: AppRelease) : AppUpdateState

    /** [release] is what a retry would fetch; [detail] is the installer's own message, if it gave one. */
    data class Failed(override val release: AppRelease, val failure: UpdateFailure, val detail: String? = null) : AppUpdateState
}

/** How the latest look for a release went. */
enum class CheckStatus { Checking, Failed, Done }
