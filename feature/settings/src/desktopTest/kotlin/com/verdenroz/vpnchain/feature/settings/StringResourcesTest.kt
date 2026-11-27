package com.verdenroz.vpnchain.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StringResourcesTest {

    @Test
    fun `no composeResources strings file escapes an apostrophe`() {
        val escaped = stringsFiles()
            .filter { "\\'" in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()

        assertEquals(emptyList(), escaped)
    }

    @Test
    fun `no composeResources strings file doubles a percent sign`() {
        assertEquals(emptyList(), stringsFiles().filter { "%%" in it.readText() }.map { it.relativeTo(root).path })
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun stringsFiles() = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
        .filter { it.name == "strings.xml" && "composeResources" in it.path }
        .toList()
}
