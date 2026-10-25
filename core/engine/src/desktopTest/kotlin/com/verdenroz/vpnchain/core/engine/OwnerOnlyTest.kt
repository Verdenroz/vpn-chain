package com.verdenroz.vpnchain.core.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OwnerOnlyTest {
    private val root: Path = Files.createTempDirectory("owner-only")

    @AfterTest
    fun tearDown() {
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        root.deleteRecursively()
    }

    private fun perms(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a new directory is created 0700`() {
        val dir = OwnerOnly.dir(root.resolve("a/b"))
        assertEquals("rwx------", perms(dir))
    }

    @Test
    fun `an existing open directory is tightened`() {
        val dir = Files.createDirectory(root.resolve("open"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
        OwnerOnly.dir(dir)
        assertEquals("rwx------", perms(dir))
    }

    @Test
    fun `a symlink in place of the directory is refused`() {
        val target = Files.createDirectory(root.resolve("elsewhere"))
        val link = Files.createSymbolicLink(root.resolve("link"), target)
        assertFailsWith<IOException> { OwnerOnly.dir(link) }
    }

    @Test
    fun `a file in place of the directory is refused`() {
        val file = Files.createFile(root.resolve("file"))
        assertFailsWith<IOException> { OwnerOnly.dir(file) }
    }
}
