package com.verdenroz.vpnchain.core.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okio.BufferedSink
import okio.FileSystem
import okio.HashingSink
import okio.IOException
import okio.Path
import okio.blackholeSink
import okio.buffer

private const val RELEASES_URL = "https://github.com/Verdenroz/vpn-chain/releases"

/** The two requests the updater makes; platforms implement them over their own HTTP stack. */
interface ReleaseTransport {
    /** @throws IOException on a transport failure or a non-2xx answer. */
    suspend fun fetchText(url: String): String

    /** Streams the body to [sink], reporting the bytes received so far. @throws IOException as [fetchText] does. */
    suspend fun download(
        url: String,
        sink: BufferedSink,
        onProgress: (Long) -> Unit,
    )
}

expect fun createReleaseTransport(): ReleaseTransport

sealed interface DownloadResult {
    data class Verified(val file: Path) : DownloadResult

    data class Failed(val reason: DownloadFailure) : DownloadResult
}

enum class DownloadFailure { NoBuild, Network, WrongSize, WrongDigest }

class ReleaseRepository(
    private val transport: ReleaseTransport,
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher,
) {
    suspend fun latest(): Result<AppRelease> =
        try {
            Result.success(parseManifest(transport.fetchText("$RELEASES_URL/latest/download/manifest.json")))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }

    /**
     * Fetches the [key] build of [release] to [dest], which only ever receives a file whose size and SHA-256 match the
     * manifest. A [dest] already holding such a file is returned without a request.
     */
    suspend fun download(
        release: AppRelease,
        key: String,
        dest: Path,
        onProgress: (Long) -> Unit = {},
    ): DownloadResult = withContext(io) { fetch(release, key, dest, onProgress) }

    private suspend fun fetch(
        release: AppRelease,
        key: String,
        dest: Path,
        onProgress: (Long) -> Unit,
    ): DownloadResult {
        val artifact = release.artifacts[key] ?: return DownloadResult.Failed(DownloadFailure.NoBuild)
        val partial = dest.parent!! / "${dest.name}.part"
        try {
            if (fileSystem.exists(dest) && failureOf(dest, artifact) { digestOf(dest) } == null) return DownloadResult.Verified(dest)
            fileSystem.createDirectories(partial.parent!!)
            val hashing = HashingSink.sha256(fileSystem.sink(partial))
            hashing.buffer().use { transport.download("$RELEASES_URL/download/v${release.versionName}/${artifact.path}", it, onProgress) }
            failureOf(partial, artifact) { hashing.hash.hex() }?.let { return DownloadResult.Failed(it) }
            fileSystem.atomicMove(partial, dest)
            return DownloadResult.Verified(dest)
        } catch (e: IOException) {
            return DownloadResult.Failed(DownloadFailure.Network)
        } finally {
            fileSystem.delete(partial)
        }
    }

    /** Deletes a build left at [dest] by an earlier run; whatever it held is installed or superseded by now. */
    fun discard(dest: Path) {
        try {
            fileSystem.delete(dest)
        } catch (e: IOException) {
            // Only wasted space; the next download replaces it.
        }
    }

    private fun failureOf(
        file: Path,
        artifact: ReleaseArtifact,
        sha256: () -> String,
    ): DownloadFailure? =
        when {
            fileSystem.metadata(file).size != artifact.size -> DownloadFailure.WrongSize
            sha256() != artifact.sha256.lowercase() -> DownloadFailure.WrongDigest
            else -> null
        }

    private fun digestOf(file: Path): String {
        val hashing = HashingSink.sha256(blackholeSink())
        fileSystem.source(file).buffer().use { it.readAll(hashing) }
        return hashing.hash.hex()
    }
}
