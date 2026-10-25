package com.verdenroz.vpnchain.core.update

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path

/**
 * Knows whether a release newer than [installedVersion] is out, and can fetch, verify and hand it to the
 * platform's [AppInstaller]. A dismissal lasts until the process ends.
 */
class AppUpdates(
    private val repository: ReleaseRepository,
    private val installer: AppInstaller,
    private val directory: Path,
    val installedVersion: String,
    private val checkEvery: Duration = 24.hours,
) {
    /** False where this copy cannot be updated from here, such as a development run. */
    val supported: Boolean = installer.artifactKey != null

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.UpToDate)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _check = MutableStateFlow(CheckStatus.Checking)
    val check: StateFlow<CheckStatus> = _check.asStateFlow()

    private val dismissed = MutableStateFlow<String?>(null)

    /** What the banner shows, or null when there is nothing to show or its release was dismissed. */
    val banner: Flow<AppUpdateState?> =
        combine(_state, dismissed) { current, dismissedVersion ->
            current.takeIf { it !is AppUpdateState.UpToDate && it.release?.versionName != dismissedVersion }
        }

    private val installs = Channel<Unit>(Channel.CONFLATED)
    private val checks = Channel<Unit>(Channel.CONFLATED)

    fun dismiss() {
        _state.value.release?.let { dismissed.value = it.versionName }
    }

    fun update() {
        installs.trySend(Unit)
    }

    fun checkNow() {
        checks.trySend(Unit)
    }

    /** Checks on entry, then each [checkEvery] or whenever [checkNow] asks, and installs when [update] asks. */
    suspend fun run() {
        val key = installer.artifactKey ?: return
        repository.discard(directory / key)
        coroutineScope {
            launch {
                for (request in installs) {
                    install(key)
                    // A tap made while that attempt ran is not a request to retry it.
                    installs.tryReceive()
                }
            }
            while (true) {
                check()
                withTimeoutOrNull(checkEvery) { checks.receive() }
            }
        }
    }

    private suspend fun check() {
        _check.value = CheckStatus.Checking
        repository.latest().fold(
            onSuccess = { release ->
                _state.update { current ->
                    when {
                        current.isBusy() -> current
                        offers(release) -> AppUpdateState.Available(release)
                        else -> AppUpdateState.UpToDate
                    }
                }
                _check.value = CheckStatus.Done
            },
            onFailure = { _check.value = CheckStatus.Failed },
        )
    }

    private fun offers(release: AppRelease): Boolean =
        isNewer(release.versionName, installedVersion) && installer.artifactKey in release.artifacts

    private suspend fun install(key: String) {
        val release = _state.value.takeIf { it.isStartable() }?.release ?: return
        if (!installer.canInstall()) {
            _state.value = AppUpdateState.NeedsPermission(release)
            installer.allowInstalls()
            return
        }
        val size = release.artifacts.getValue(key).size.coerceAtLeast(1)
        var percent = 0
        _state.value = AppUpdateState.Downloading(release, 0f)
        // Named by key: the manifest's own path is not trusted as a place to write.
        val result = repository.download(release, key, directory / key) { received ->
            val now = (received * 100 / size).toInt().coerceAtMost(100)
            if (now != percent) {
                percent = now
                _state.value = AppUpdateState.Downloading(release, now / 100f)
            }
        }
        _state.value = when (result) {
            is DownloadResult.Failed -> AppUpdateState.Failed(release, result.reason.toFailure())
            is DownloadResult.Verified -> handOff(release, result.file)
        }
    }

    private suspend fun handOff(release: AppRelease, file: Path): AppUpdateState {
        _state.value = AppUpdateState.Installing(release)
        return when (val result = installer.install(file, release)) {
            InstallResult.Started -> AppUpdateState.Ready(release)
            InstallResult.NeedsPermission -> AppUpdateState.NeedsPermission(release)
            is InstallResult.Failed -> AppUpdateState.Failed(release, UpdateFailure.Install, result.message)
        }
    }
}

private fun AppUpdateState.isBusy() =
    this is AppUpdateState.Downloading || this is AppUpdateState.Installing || this is AppUpdateState.Ready

private fun AppUpdateState.isStartable() =
    this is AppUpdateState.Available || this is AppUpdateState.NeedsPermission || this is AppUpdateState.Failed

private fun DownloadFailure.toFailure() =
    when (this) {
        DownloadFailure.NoBuild -> UpdateFailure.NoBuild
        DownloadFailure.Network -> UpdateFailure.Network
        DownloadFailure.WrongSize, DownloadFailure.WrongDigest -> UpdateFailure.Corrupt
    }
