package com.verdenroz.vpnchain.core.config

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopLearnedRuleSetStoreTest {

    private val root = Files.createTempDirectory("learned-store").toFile()
    private val run = File(root, "run").apply { mkdirs() }
    private val private = File(root, "private").apply { mkdirs() }
    private var managed = true
    private val store = DesktopLearnedRuleSetStore(managed = { managed }, runDir = { run }, privateDir = { private })

    @Test
    fun `the directory chosen for a session holds until the next one`() {
        store.beginSession()
        val path = store.path(RuleSetKind.Open)
        managed = false

        store.write(RuleSetKind.Open, LearnedRules(exact = listOf("www.shop.example")))

        assertEquals(File(run, "learned-open.json").absolutePath, path)
        assertTrue(File(run, "learned-open.json").exists())
        assertEquals(emptyList(), private.list().orEmpty().toList())
    }

    @Test
    fun `a new session picks the directory again`() {
        store.beginSession()
        managed = false
        store.beginSession()

        assertEquals(File(private, "learned-warp.json").absolutePath, store.path(RuleSetKind.Warp))
    }

    @Test
    fun `an unwritable session directory is an error, not a quiet move`() {
        store.beginSession()
        Files.setPosixFilePermissions(run.toPath(), PosixFilePermissions.fromString("r-x------"))
        managed = false

        assertFailsWith<IOException> { store.write(RuleSetKind.Direct, LearnedRules()) }
        assertEquals(emptyList(), private.list().orEmpty().toList())
    }
}
