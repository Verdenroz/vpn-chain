package com.verdenroz.vpnchain.core.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVersionTest {

    @Test
    fun `a later patch, minor or major is newer`() {
        assertTrue(isNewer("0.1.7", "0.1.6"))
        assertTrue(isNewer("0.2.0", "0.1.9"))
        assertTrue(isNewer("1.0.0", "0.99.99"))
    }

    @Test
    fun `numbers compare as numbers, not text`() {
        assertTrue(isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun `the same or an earlier version is not newer`() {
        assertFalse(isNewer("0.1.6", "0.1.6"))
        assertFalse(isNewer("0.1.5", "0.1.6"))
    }

    @Test
    fun `a name that is not major minor patch is never newer`() {
        assertFalse(isNewer("0.2.0-rc1", "0.1.6"))
        assertFalse(isNewer("0.2", "0.1.6"))
        assertFalse(isNewer("0.2.0", "dev"))
        assertFalse(isNewer("", "0.1.6"))
    }
}
