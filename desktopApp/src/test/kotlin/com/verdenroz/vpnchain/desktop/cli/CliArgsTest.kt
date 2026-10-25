package com.verdenroz.vpnchain.desktop.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliArgsTest {

    @Test
    fun `no arguments asks for help`() {
        assertEquals(Invocation(Command.Help), parse(emptyList()))
    }

    @Test
    fun `up takes an optional name and waits by default`() {
        assertEquals(Invocation(Command.Up(profile = null, wait = true)), parse(listOf("up")))
        assertEquals(Invocation(Command.Up(profile = "Work", wait = false)), parse(listOf("up", "--no-wait", "Work")))
    }

    @Test
    fun `json is accepted anywhere`() {
        assertEquals(Invocation(Command.Status(watch = false), json = true), parse(listOf("--json", "status")))
        assertEquals(Invocation(Command.List, json = true), parse(listOf("list", "--json")))
    }

    @Test
    fun `status can watch`() {
        assertEquals(Invocation(Command.Status(watch = true)), parse(listOf("status", "--watch")))
    }

    @Test
    fun `use needs a name`() {
        assertEquals(Invocation(Command.Use("home")), parse(listOf("use", "home")))
        assertFailsWith<UsageException> { parse(listOf("use")) }
    }

    @Test
    fun `logs takes a positive count`() {
        assertEquals(Invocation(Command.Logs(null)), parse(listOf("logs")))
        assertEquals(Invocation(Command.Logs(20)), parse(listOf("logs", "-n", "20")))
        assertFailsWith<UsageException> { parse(listOf("logs", "-n", "0")) }
        assertFailsWith<UsageException> { parse(listOf("logs", "-n")) }
    }

    @Test
    fun `unknown commands and leftovers are refused`() {
        assertFailsWith<UsageException> { parse(listOf("render")) }
        assertFailsWith<UsageException> { parse(listOf("down", "now")) }
        assertFailsWith<UsageException> { parse(listOf("status", "--verbose")) }
    }
}
