package com.verdenroz.vpnchain.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class EnginePathsTest {
    private val both = mapOf("XDG_RUNTIME_DIR" to "/run/user/1000", "TMPDIR" to "/var/folders/ab/T/")

    @Test
    fun `XDG_RUNTIME_DIR wins everywhere`() {
        assertEquals("/run/user/1000", EnginePaths.runtimeBase(both::get, macOS = true))
        assertEquals("/run/user/1000", EnginePaths.runtimeBase(both::get, macOS = false))
    }

    @Test
    fun `macOS falls back to its per-user TMPDIR, then tmp`() {
        assertEquals("/var/folders/ab/T/", EnginePaths.runtimeBase(mapOf("TMPDIR" to "/var/folders/ab/T/")::get, macOS = true))
        assertEquals("/tmp", EnginePaths.runtimeBase(emptyMap<String, String>()::get, macOS = true))
    }

    @Test
    fun `Linux ignores TMPDIR and uses tmp`() {
        assertEquals("/tmp", EnginePaths.runtimeBase(mapOf("TMPDIR" to "/home/me/tmp")::get, macOS = false))
    }
}
