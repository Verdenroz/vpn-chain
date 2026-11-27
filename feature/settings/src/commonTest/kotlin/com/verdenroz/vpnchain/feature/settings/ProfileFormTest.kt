package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.feature.settings.component.keepingPairing
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileFormTest {

    private val paired = ChainProfile(
        vpsIp = "203.0.113.10",
        vlessUuid = "u",
        realityPublicKey = "k",
        shortId = "ab",
        managementKeyId = "0011223344556677",
        managementAdmin = true,
        managementDevice = "desktop",
    )

    @Test
    fun `an edited relay keeps how the device was paired`() {
        val edited = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "u2", realityPublicKey = "k", shortId = "cd")

        val saved = edited.keepingPairing(paired)

        assertEquals(paired.copy(vlessUuid = "u2", shortId = "cd"), saved)
    }

    @Test
    fun `pointing at another relay drops the pairing`() {
        val other = ChainProfile(vpsIp = "203.0.113.11", vlessUuid = "u", realityPublicKey = "k", shortId = "ab")
        val newKey = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "u", realityPublicKey = "k2", shortId = "ab")

        assertEquals(other, other.keepingPairing(paired))
        assertEquals(newKey, newKey.keepingPairing(paired))
    }

    @Test
    fun `a relay typed in from scratch stays unpaired`() {
        val typed = ChainProfile(vpsIp = "203.0.113.11", vlessUuid = "u", realityPublicKey = "k", shortId = "cd")

        assertEquals(typed, typed.keepingPairing(null))
    }
}
