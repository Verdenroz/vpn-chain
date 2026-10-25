package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkTrackerTest {

    @Test
    fun `the first link seen is not a change`() {
        assertFalse(LinkTracker<String>().available("wifi"))
    }

    @Test
    fun `a handover to another link is a change`() {
        val tracker = LinkTracker<String>()
        tracker.available("wifi")

        assertTrue(tracker.available("cell"))
    }

    @Test
    fun `the same link reported again is not a change`() {
        val tracker = LinkTracker<String>()
        tracker.available("wifi")

        assertFalse(tracker.available("wifi"))
    }

    @Test
    fun `a link returning after an outage is a change`() {
        val tracker = LinkTracker<String>()
        tracker.available("wifi")
        assertTrue(tracker.lost("wifi"))

        assertTrue(tracker.available("wifi"))
    }

    @Test
    fun `the regain after an outage is reported once`() {
        val tracker = LinkTracker<String>()
        tracker.available("wifi")
        tracker.lost("wifi")
        tracker.available("cell")

        assertFalse(tracker.available("cell"))
    }

    @Test
    fun `losing a link we are not on is not a change`() {
        val tracker = LinkTracker<String>()
        tracker.available("wifi")
        tracker.available("cell")

        assertFalse(tracker.lost("wifi"))
        assertFalse(tracker.available("cell"))
    }
}
