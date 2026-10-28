package com.verdenroz.vpnchain.core.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedEntryFixturesTest {

    private val dir = File("../../relay/crates/relayd/tests/fixtures/entry")

    @Test
    fun `every shared fixture gets the verdict relayd gives it`() {
        val expected = File(dir, "expected.tsv").readLines().filter(String::isNotBlank).map { it.split('\t') }
        assertTrue(expected.size > 20)
        for ((name, verdict) in expected) {
            val result = WireGuardConfParser.parse(File(dir, "$name.conf").readText())
            assertEquals(verdict, result.exceptionOrNull()?.message ?: "ok", name)
        }
    }

    @Test
    fun `the fixture directory holds nothing the table skips`() {
        val listed = File(dir, "expected.tsv").readLines().map { it.substringBefore('\t') }.toSet()
        val files = dir.listFiles { file -> file.extension == "conf" }.orEmpty().map { it.nameWithoutExtension }.toSet()

        assertEquals(files, listed)
    }
}
