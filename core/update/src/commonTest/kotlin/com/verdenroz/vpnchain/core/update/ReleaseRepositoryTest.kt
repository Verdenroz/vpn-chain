package com.verdenroz.vpnchain.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private const val BODY = "apk bytes"
private val BODY_SHA256 = BODY.encodeUtf8().sha256().hex()
private val DEST = "/cache/update/vpn-chain.apk".toPath()

class ReleaseRepositoryTest {
    private val fileSystem = FakeFileSystem()
    private val apkUrl = "https://github.com/Verdenroz/vpn-chain/releases/download/v0.1.7/vpn-chain-0.1.7-android-arm64-v8a.apk"
    private val manifestUrl = "https://github.com/Verdenroz/vpn-chain/releases/latest/download/manifest.json"

    private fun release(size: Long = BODY.length.toLong(), sha256: String = BODY_SHA256) = AppRelease(
        versionName = "0.1.7",
        artifacts = mapOf(
            "android-arm64-v8a" to ReleaseArtifact("vpn-chain-0.1.7-android-arm64-v8a.apk", size, sha256),
        ),
    )

    private fun TestScope.repository(transport: FakeTransport) =
        ReleaseRepository(transport, fileSystem, StandardTestDispatcher(testScheduler))

    @Test
    fun `reads the latest release's manifest`() = runTest {
        val manifest = """{"version":1,"versionName":"0.1.7","versionCode":107,"artifacts":{}}"""

        val result = repository(FakeTransport(mapOf(manifestUrl to manifest))).latest()

        assertEquals("0.1.7", result.getOrThrow().versionName)
    }

    @Test
    fun `a missing manifest is a failure`() = runTest {
        assertTrue(repository(FakeTransport(emptyMap())).latest().isFailure)
    }

    @Test
    fun `a manifest that is not json is a failure`() = runTest {
        assertTrue(repository(FakeTransport(mapOf(manifestUrl to "<html>"))).latest().isFailure)
    }

    @Test
    fun `keeps a download whose size and digest match`() = runTest {
        val transport = FakeTransport(mapOf(apkUrl to BODY))
        val progress = mutableListOf<Long>()

        val result = repository(transport).download(release(), "android-arm64-v8a", DEST) { progress += it }

        assertEquals(DownloadResult.Verified(DEST), result)
        assertEquals(BODY, fileSystem.read(DEST) { readUtf8() })
        assertEquals(BODY.length.toLong(), progress.last())
    }

    @Test
    fun `refuses a download of the wrong size and leaves nothing behind`() = runTest {
        val result = repository(FakeTransport(mapOf(apkUrl to BODY)))
            .download(release(size = BODY.length + 1L), "android-arm64-v8a", DEST)

        assertEquals(DownloadResult.Failed(DownloadFailure.WrongSize), result)
        assertFalse(fileSystem.exists(DEST))
        assertTrue(fileSystem.list(DEST.parent!!).isEmpty())
    }

    @Test
    fun `refuses a download with the wrong digest and leaves nothing behind`() = runTest {
        val result = repository(FakeTransport(mapOf(apkUrl to BODY)))
            .download(release(sha256 = "0".repeat(64)), "android-arm64-v8a", DEST)

        assertEquals(DownloadResult.Failed(DownloadFailure.WrongDigest), result)
        assertFalse(fileSystem.exists(DEST))
        assertTrue(fileSystem.list(DEST.parent!!).isEmpty())
    }

    @Test
    fun `a digest in upper case still matches`() = runTest {
        val result = repository(FakeTransport(mapOf(apkUrl to BODY)))
            .download(release(sha256 = BODY_SHA256.uppercase()), "android-arm64-v8a", DEST)

        assertIs<DownloadResult.Verified>(result)
    }

    @Test
    fun `reuses a verified file already there without a request`() = runTest {
        val transport = FakeTransport(emptyMap())
        fileSystem.createDirectories(DEST.parent!!)
        fileSystem.write(DEST) { writeUtf8(BODY) }

        val result = repository(transport).download(release(), "android-arm64-v8a", DEST)

        assertEquals(DownloadResult.Verified(DEST), result)
        assertTrue(transport.requested.isEmpty())
    }

    @Test
    fun `replaces a file that no longer matches`() = runTest {
        fileSystem.createDirectories(DEST.parent!!)
        fileSystem.write(DEST) { writeUtf8("stale") }

        val result = repository(FakeTransport(mapOf(apkUrl to BODY))).download(release(), "android-arm64-v8a", DEST)

        assertEquals(DownloadResult.Verified(DEST), result)
        assertEquals(BODY, fileSystem.read(DEST) { readUtf8() })
    }

    @Test
    fun `a transport failure is reported and leaves nothing behind`() = runTest {
        val result = repository(FakeTransport(emptyMap())).download(release(), "android-arm64-v8a", DEST)

        assertEquals(DownloadResult.Failed(DownloadFailure.Network), result)
        assertTrue(fileSystem.list(DEST.parent!!).isEmpty())
    }

    @Test
    fun `a release without this build cannot be downloaded`() = runTest {
        val result = repository(FakeTransport(emptyMap())).download(release(), "linux-x64", DEST)

        assertEquals(DownloadResult.Failed(DownloadFailure.NoBuild), result)
    }
}
