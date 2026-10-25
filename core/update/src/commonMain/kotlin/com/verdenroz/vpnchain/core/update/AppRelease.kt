package com.verdenroz.vpnchain.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One downloadable build: [path] under the release's download URL, its [size] in bytes and lowercase hex [sha256]. */
@Serializable
data class ReleaseArtifact(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** What a release's `manifest.json` carries; [artifacts] is keyed by build, like `android-arm64-v8a` or `linux-x64`. */
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val artifacts: Map<String, ReleaseArtifact>,
)

@Serializable
private class WireManifest(
    val version: Int,
    val versionName: String,
    val versionCode: Int,
    val artifacts: Map<String, ReleaseArtifact>,
)

private const val MANIFEST_VERSION = 1

private val json = Json { ignoreUnknownKeys = true }

internal fun parseManifest(text: String): AppRelease {
    val wire = json.decodeFromString<WireManifest>(text)
    require(wire.version == MANIFEST_VERSION) { "manifest version ${wire.version}" }
    return AppRelease(wire.versionName, wire.versionCode, wire.artifacts)
}
