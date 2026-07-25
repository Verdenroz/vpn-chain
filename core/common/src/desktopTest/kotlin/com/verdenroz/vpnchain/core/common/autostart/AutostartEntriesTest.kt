package com.verdenroz.vpnchain.core.common.autostart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutostartEntriesTest {

    @Test
    fun `linux entry launches the given binary at login`() {
        val entry = AutostartEntries.linuxDesktopEntry("/opt/vpn-chain/bin/vpn-chain")

        assertTrue(entry.startsWith("[Desktop Entry]"), entry)
        assertTrue("Exec=/opt/vpn-chain/bin/vpn-chain" in entry, entry)
        assertTrue("Type=Application" in entry, entry)
    }

    /**
     * An unquoted `Exec` splits on spaces, so a install path like
     * "/home/me/My Apps/vpn-chain" would launch "/home/me/My" with an argument.
     */
    @Test
    fun `linux entry quotes an exec path containing spaces`() {
        val entry = AutostartEntries.linuxDesktopEntry("/home/me/My Apps/vpn-chain")

        assertTrue("""Exec="/home/me/My Apps/vpn-chain"""" in entry, entry)
    }

    @Test
    fun `mac plist runs the binary at load`() {
        val plist = AutostartEntries.macLaunchAgentPlist("/Applications/VPN Chain.app/Contents/MacOS/vpn-chain")

        assertTrue("<key>RunAtLoad</key>" in plist, plist)
        assertTrue("<true/>" in plist, plist)
        assertTrue("<string>${AutostartEntries.LABEL}</string>" in plist, plist)
        assertTrue("/Applications/VPN Chain.app/Contents/MacOS/vpn-chain" in plist, plist)
    }

    /** A path is user-controlled text landing in XML; `&` alone breaks the plist. */
    @Test
    fun `mac plist escapes xml-significant characters in the path`() {
        val plist = AutostartEntries.macLaunchAgentPlist("/Apps/R&D/vpn-chain")

        assertTrue("/Apps/R&amp;D/vpn-chain" in plist, plist)
        assertTrue("R&D" !in plist, "raw ampersand must not survive into the plist")
    }

    @Test
    fun `windows registration passes the path as its own argv entry`() {
        val argv = AutostartEntries.windowsRegisterArgv("""C:\Program Files\VPN Chain\vpn-chain.exe""")

        assertEquals("reg", argv.first())
        assertTrue("add" in argv)
        assertTrue(AutostartEntries.REGISTRY_RUN_KEY in argv)
        assertTrue(AutostartEntries.REGISTRY_VALUE_NAME in argv)
        // Quoted for Windows' own re-parse of the Run value, but still a single
        // argv element so no shell ever splits it.
        assertTrue(""""C:\Program Files\VPN Chain\vpn-chain.exe"""" in argv, argv.toString())
    }

    @Test
    fun `windows deregistration targets the same value it registered`() {
        val argv = AutostartEntries.windowsUnregisterArgv()

        assertTrue("delete" in argv)
        assertTrue(AutostartEntries.REGISTRY_RUN_KEY in argv)
        assertTrue(AutostartEntries.REGISTRY_VALUE_NAME in argv)
    }
}
