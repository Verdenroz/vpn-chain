package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

class PlatformTunnelConfigTest {

    private val relayProfile = SavedProfile(id = "p", name = "p", relay = profile(), deviceExit = deviceExit())

    @Test
    fun `the loopback proxy renders the exit`() {
        val config = parse(renderPlatformTunnelConfig(relayProfile, UserSettings(systemWideTun = false), warp = null, learned = null))

        assertNotNull(config.array("endpoints").taggedWith("device-exit"))
        assertEquals("device-exit", config.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
    }

    @Test
    fun `the tun chain renders the exit`() {
        val config = parse(renderPlatformTunnelConfig(relayProfile, UserSettings(systemWideTun = true), warp = null, learned = null))

        assertNotNull(config.array("endpoints").taggedWith("device-exit"))
    }

    @Test
    fun `a wireguard only profile never renders an exit`() {
        val bare = SavedProfile(id = "w", name = "w", wireGuard = entry())

        val config = renderPlatformTunnelConfig(bare, UserSettings(), warp = null, learned = null)

        assertFalse("device-exit" in config)
    }
}
