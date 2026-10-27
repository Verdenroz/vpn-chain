package com.verdenroz.vpnchain.core.tunnel

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BundledToolTest {

    private val dir = Files.createTempDirectory("bundled-tool").toFile()

    @Test
    fun `an executable in the package directory wins over the PATH`() {
        val tool = dir.resolve("sing-box").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

        assertEquals(tool.path, bundledTool("sing-box", dir))
    }

    @Test
    fun `a source build falls back to the bare name`() {
        assertEquals("sing-box", bundledTool("sing-box", dir))
    }

    @Test
    fun `a file that is not executable is ignored`() {
        dir.resolve("sing-box").writeText("not a program")

        assertEquals("sing-box", bundledTool("sing-box", dir))
    }
}
