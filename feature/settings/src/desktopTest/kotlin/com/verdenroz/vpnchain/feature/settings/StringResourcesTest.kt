package com.verdenroz.vpnchain.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StringResourcesTest {

    @Test
    fun `no composeResources strings file escapes an apostrophe`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val escaped = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.name == "strings.xml" && "composeResources" in it.path }
            .filter { "\\'" in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()

        assertEquals(emptyList(), escaped)
    }
}
