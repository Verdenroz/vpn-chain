package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PASSES = 8

/**
 * Pushes dots apart until each pair is at least [minGap] away, so hops in the
 * same city or country stay separate dots with a leg between them. Points
 * that already clear the gap do not move.
 */
internal fun spread(points: List<Offset>, minGap: Float): List<Offset> {
    val out = points.toMutableList()
    repeat(PASSES) {
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val dx = out[j].x - out[i].x
                val dy = out[j].y - out[i].y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance >= minGap) continue
                val direction = if (distance > 0f) {
                    Offset(dx / distance, dy / distance)
                } else {
                    val angle = (i + j) * 2.0 * PI / 3.0
                    Offset(cos(angle).toFloat(), sin(angle).toFloat())
                }
                val push = (minGap - distance) / 2f
                out[i] = out[i] - direction * push
                out[j] = out[j] + direction * push
            }
        }
    }
    return out
}
