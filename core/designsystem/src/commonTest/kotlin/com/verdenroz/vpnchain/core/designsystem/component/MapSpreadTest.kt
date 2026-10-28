package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapSpreadTest {
    private fun distance(a: Offset, b: Offset) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    @Test
    fun clearDotsStayWhereTheyAre() {
        val points = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(0f, 100f))
        assertEquals(points, spread(points, minGap = 30f))
    }

    @Test
    fun overlappingDotsEndAtLeastTheGapApart() {
        val out = spread(listOf(Offset(50f, 50f), Offset(58f, 50f)), minGap = 30f)
        assertTrue(distance(out[0], out[1]) >= 29.9f)
    }

    @Test
    fun separationKeepsTheMidpoint() {
        val out = spread(listOf(Offset(50f, 50f), Offset(58f, 50f)), minGap = 30f)
        assertEquals(54f, (out[0].x + out[1].x) / 2f, 0.01f)
    }

    @Test
    fun threeDotsAtOnePointAllSeparate() {
        val at = Offset(10f, 10f)
        val out = spread(listOf(at, at, at), minGap = 30f)
        for (i in out.indices) for (j in i + 1 until out.size) {
            assertTrue(distance(out[i], out[j]) >= 29f, "dots $i and $j")
        }
    }

    @Test
    fun spreadIsDeterministic() {
        val at = Offset(10f, 10f)
        assertEquals(spread(listOf(at, at, at), 30f), spread(listOf(at, at, at), 30f))
    }
}
