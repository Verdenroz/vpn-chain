package com.verdenroz.vpnchain.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private const val BODY = "apk bytes"
private const val KEY = "android-arm64-v8a"
private const val MANIFEST_URL = "https://github.com/Verdenroz/vpn-chain/releases/latest/download/manifest.json"

private fun manifest(version: String, sha256: String = BODY.encodeUtf8().sha256().hex(), keys: List<String> = listOf(KEY)) =
    """
    {"version":1,"versionName":"$version","versionCode":1,"artifacts":{
      ${keys.joinToString(",") { """"$it":{"path":"vpn-chain-$version-$it.apk","size":${BODY.length},"sha256":"$sha256"}""" }}}}
    """

private fun apkUrl(version: String) =
    "https://github.com/Verdenroz/vpn-chain/releases/download/v$version/vpn-chain-$version-$KEY.apk"

private class FakeInstaller(override val artifactKey: String? = KEY) : AppInstaller {
    var allowed = true
    var result: InstallResult = InstallResult.Started
    var settingsOpened = 0
    val installed = mutableListOf<Path>()

    override suspend fun install(file: Path, release: AppRelease): InstallResult {
        installed += file
        return result
    }

    override fun canInstall() = allowed

    override fun allowInstalls() {
        settingsOpened++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private val fileSystem = FakeFileSystem()
    private val installer = FakeInstaller()

    private fun TestScope.updates(
        pages: Map<String, String>,
        installed: String = "0.1.6",
        installer: AppInstaller = this@AppUpdatesTest.installer,
    ): Pair<AppUpdates, FakeTransport> {
        val transport = FakeTransport(pages)
        val updates = AppUpdates(ReleaseRepository(transport, fileSystem, StandardTestDispatcher(testScheduler)), installer, "/cache/update".toPath(), installed)
        backgroundScope.launch { updates.run() }
        return updates to transport
    }

    private val newer = mapOf(MANIFEST_URL to manifest("0.1.7"), apkUrl("0.1.7") to BODY)

    @Test
    fun `offers a newer release`() = runTest {
        val (updates) = updates(newer)
        runCurrent()

        assertEquals("0.1.7", assertIs<AppUpdateState.Available>(updates.state.value).release.versionName)
        assertEquals(CheckStatus.Done, updates.check.value)
    }

    @Test
    fun `offers nothing for the installed version`() = runTest {
        val (updates) = updates(newer, installed = "0.1.7")
        runCurrent()

        assertEquals(AppUpdateState.UpToDate, updates.state.value)
        assertEquals(CheckStatus.Done, updates.check.value)
    }

    @Test
    fun `offers nothing for a manifest version it cannot order`() = runTest {
        for (name in listOf("0.2.0-rc1", "")) {
            val (updates) = updates(mapOf(MANIFEST_URL to manifest(name)))
            runCurrent()

            assertEquals(AppUpdateState.UpToDate, updates.state.value)
            assertEquals(CheckStatus.Done, updates.check.value)
        }
    }

    @Test
    fun `offers nothing when the release has no build for this device`() = runTest {
        val (updates) = updates(mapOf(MANIFEST_URL to manifest("0.1.7", keys = listOf("android-x86_64"))))
        runCurrent()

        assertEquals(AppUpdateState.UpToDate, updates.state.value)
    }

    @Test
    fun `a failed check changes nothing but the check status`() = runTest {
        val (updates) = updates(emptyMap())
        runCurrent()

        assertEquals(AppUpdateState.UpToDate, updates.state.value)
        assertEquals(CheckStatus.Failed, updates.check.value)
    }

    @Test
    fun `checks again every day`() = runTest {
        val (updates, transport) = updates(mapOf(MANIFEST_URL to manifest("0.1.6")))
        runCurrent()
        advanceTimeBy(23.hours)
        runCurrent()
        assertEquals(1, transport.requested.size)

        advanceTimeBy(1.hours)
        runCurrent()

        assertEquals(2, transport.requested.size)
        assertEquals(CheckStatus.Done, updates.check.value)
    }

    @Test
    fun `check now does not wait for the day`() = runTest {
        val (updates, transport) = updates(mapOf(MANIFEST_URL to manifest("0.1.6")))
        runCurrent()

        updates.checkNow()
        runCurrent()

        assertEquals(2, transport.requested.size)
    }

    @Test
    fun `downloads, verifies and hands the build to the installer`() = runTest {
        val (updates) = updates(newer)
        runCurrent()

        updates.update()
        runCurrent()

        assertIs<AppUpdateState.Ready>(updates.state.value)
        assertEquals(listOf("/cache/update/$KEY".toPath()), installer.installed)
    }

    @Test
    fun `a corrupt download is not installed`() = runTest {
        val (updates) = updates(mapOf(MANIFEST_URL to manifest("0.1.7"), apkUrl("0.1.7") to "tampered"))
        runCurrent()

        updates.update()
        runCurrent()

        assertEquals(UpdateFailure.Corrupt, assertIs<AppUpdateState.Failed>(updates.state.value).failure)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `a failed download can be retried`() = runTest {
        val (updates) = updates(mapOf(MANIFEST_URL to manifest("0.1.7")))
        runCurrent()
        updates.update()
        runCurrent()
        assertEquals(UpdateFailure.Network, assertIs<AppUpdateState.Failed>(updates.state.value).failure)

        updates.update()
        runCurrent()

        assertEquals(UpdateFailure.Network, assertIs<AppUpdateState.Failed>(updates.state.value).failure)
    }

    @Test
    fun `an installer refusal carries its message`() = runTest {
        installer.result = InstallResult.Failed("blocked")
        val (updates) = updates(newer)
        runCurrent()

        updates.update()
        runCurrent()

        val failed = assertIs<AppUpdateState.Failed>(updates.state.value)
        assertEquals(UpdateFailure.Install, failed.failure)
        assertEquals("blocked", failed.detail)
    }

    @Test
    fun `asks for permission before downloading, then goes on once it is given`() = runTest {
        installer.allowed = false
        val (updates, transport) = updates(newer)
        runCurrent()

        updates.update()
        runCurrent()

        assertIs<AppUpdateState.NeedsPermission>(updates.state.value)
        assertEquals(1, installer.settingsOpened)
        assertEquals(1, transport.requested.size)

        installer.allowed = true
        updates.update()
        runCurrent()

        assertIs<AppUpdateState.Ready>(updates.state.value)
    }

    @Test
    fun `a tap made during an attempt does not retry it`() = runTest {
        val verdict = CompletableDeferred<InstallResult>()
        val slow = object : AppInstaller by installer {
            var calls = 0

            override suspend fun install(file: Path, release: AppRelease): InstallResult {
                calls++
                return verdict.await()
            }
        }
        val (updates) = updates(newer, installer = slow)
        runCurrent()
        updates.update()
        runCurrent()

        updates.update()
        verdict.complete(InstallResult.Failed("declined"))
        runCurrent()

        assertEquals(1, slow.calls)
        assertIs<AppUpdateState.Failed>(updates.state.value)
    }

    @Test
    fun `drops a build an earlier run left behind`() = runTest {
        val stale = "/cache/update/$KEY".toPath()
        fileSystem.createDirectories(stale.parent!!)
        fileSystem.write(stale) { writeUtf8(BODY) }

        updates(newer)
        runCurrent()

        assertTrue(!fileSystem.exists(stale))
    }

    @Test
    fun `reports download progress`() = runTest {
        val (updates) = updates(newer)
        runCurrent()
        val seen = mutableListOf<Float>()
        backgroundScope.launch { updates.state.collect { (it as? AppUpdateState.Downloading)?.let { d -> seen += d.progress } } }

        updates.update()
        runCurrent()

        assertEquals(1f, seen.max())
        assertTrue(seen.size > 2)
    }

    @Test
    fun `dismissing hides that version and a newer one shows again`() = runTest {
        val pages = newer.toMutableMap()
        val (updates) = updates(pages)
        runCurrent()
        assertIs<AppUpdateState.Available>(updates.banner.first())

        updates.dismiss()
        assertNull(updates.banner.first())

        pages[MANIFEST_URL] = manifest("0.1.8")
        updates.checkNow()
        runCurrent()
        assertEquals("0.1.8", assertIs<AppUpdateState.Available>(updates.banner.first()).release.versionName)
    }

    @Test
    fun `a copy that cannot be updated makes no request`() = runTest {
        val (updates, transport) = updates(newer, installer = FakeInstaller(artifactKey = null))
        runCurrent()

        assertTrue(transport.requested.isEmpty())
        assertEquals(false, updates.supported)
    }
}
