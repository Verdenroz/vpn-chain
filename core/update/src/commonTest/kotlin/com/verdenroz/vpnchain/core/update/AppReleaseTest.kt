package com.verdenroz.vpnchain.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppReleaseTest {

    @Test
    fun `parses the manifest the release workflow writes`() {
        val release = parseManifest(
            """
            {"version":1,"versionName":"0.1.7","versionCode":107,"artifacts":{
              "android-arm64-v8a":{"path":"vpn-chain-0.1.7-android-arm64-v8a.apk","size":27,"sha256":"aa"},
              "linux-x64":{"path":"vpn-chain-0.1.7-linux-x64.tar.gz","size":22,"sha256":"bb"}}}
            """,
        )

        assertEquals("0.1.7", release.versionName)
        assertEquals(107, release.versionCode)
        assertEquals(ReleaseArtifact("vpn-chain-0.1.7-linux-x64.tar.gz", 22, "bb"), release.artifacts["linux-x64"])
        assertEquals(setOf("android-arm64-v8a", "linux-x64"), release.artifacts.keys)
    }

    @Test
    fun `ignores fields a later manifest adds`() {
        val release = parseManifest(
            """{"version":1,"versionName":"0.1.7","versionCode":107,"notes":"x","artifacts":{}}""",
        )

        assertEquals(emptyMap(), release.artifacts)
    }

    @Test
    fun `rejects a manifest version it does not know`() {
        assertFailsWith<IllegalArgumentException> {
            parseManifest("""{"version":2,"versionName":"0.1.7","versionCode":107,"artifacts":{}}""")
        }
    }
}
