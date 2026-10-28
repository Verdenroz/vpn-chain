package com.verdenroz.vpnchain.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteMapFitTest {
    private fun node(lat: Double, lon: Double, kind: NodeKind) = MapNode("", lat, lon, kind)

    private val vancouver = node(49.3, -123.1, NodeKind.Origin)
    private val tokyo = node(35.7, 139.7, NodeKind.Exit)
    private val toronto = node(43.7, -79.4, NodeKind.Origin)
    private val frankfurt = node(50.1, 8.7, NodeKind.Exit)

    @Test
    fun noNodesShowsTheWholeWorld() {
        val camera = fit(emptyList(), aspect = 1f)
        assertEquals(360f, camera.spanLon)
        assertFalse(camera.wrapped)
    }

    @Test
    fun transatlanticRouteIsNotWrapped() {
        val camera = fit(listOf(toronto, frankfurt), aspect = 1f)
        assertFalse(camera.wrapped)
        assertEquals((-79.4f + 8.7f) / 2f, camera.centerLon, 0.01f)
    }

    @Test
    fun pacificRouteTakesTheShortWayRound() {
        val camera = fit(listOf(vancouver, tokyo), aspect = 1f)
        assertTrue(camera.wrapped)
        assertEquals((-123.1f + 360f + 139.7f) / 2f, camera.centerLon, 0.01f)
        assertTrue(camera.spanLon < 360f)
    }

    @Test
    fun singleNodeStopsAtTheMinimumSpan() {
        assertEquals(24f, fit(listOf(frankfurt), aspect = 1f).spanLon)
    }

    @Test
    fun routeInsideOneCountryFramesJustThatRoute() {
        val camera = fit(listOf(node(40.7, -74.0, NodeKind.Origin), node(25.8, -80.2, NodeKind.Entry)), aspect = 1.5f)
        assertFalse(camera.wrapped)
        assertTrue(camera.spanLon < 80f)
        assertEquals((40.7f + 25.8f) / 2f, camera.centerLat, 0.01f)
        assertEquals((-74.0f + -80.2f) / 2f, camera.centerLon, 0.01f)
    }

    @Test
    fun hopsInOneCityStopAtTheMinimumSpan() {
        val city = node(40.7, -74.0, NodeKind.Origin)
        assertEquals(24f, fit(listOf(city, city.copy(kind = NodeKind.Entry)), aspect = 1.5f).spanLon)
    }
}
