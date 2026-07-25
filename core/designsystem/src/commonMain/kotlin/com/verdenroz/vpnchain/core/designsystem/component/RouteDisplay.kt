package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.generated.resources.Res
import com.verdenroz.vpnchain.core.designsystem.generated.resources.world_landmass
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs

/**
 * One plotted hop. Carries only a short label: the plate says *where*, and the
 * rail beneath it says which address — printing both here makes each block big
 * enough to collide with its neighbours.
 */
data class RoutePoint(
    val label: String,
    val lat: Double,
    val lon: Double,
    val lamp: Color,
    /** Traffic is passing through this hop right now. */
    val carrying: Boolean,
)

/**
 * The route as a display sunk into the panel: an equirectangular landmass plate
 * with the chain's hops plotted in order and packets visibly moving between
 * them. Every leg drawn corresponds to a hop the caller could actually observe
 * — an unresolved hop is simply absent rather than guessed at.
 */
@Composable
fun RouteDisplay(
    points: List<RoutePoint>,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val flow = rememberInfiniteTransition(label = "route-flow")
    // One shared clock: dashes crawl and packets travel off the same phase, so
    // the whole display reads as one mechanism.
    val phase by flow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "flow-phase",
    )

    Box(
        modifier
            .fillMaxWidth()
            // The plate is a true equirectangular 2:1 — any other ratio would
            // stretch the world and quietly move every hop off its coordinates.
            .aspectRatio(2f)
            .machinedSurface(colors, corner = 2.dp, recessed = true, fill = colors.screenWell)
            .padding(1.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.world_landmass),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(colors.screenLand),
        )

        Canvas(Modifier.fillMaxSize()) {
            drawGraticule(colors.screenLand)

            val plotted = points.mapNotNull { point ->
                project(point)?.let { point to it }
            }

            plotted.zipWithNext { (fromPoint, from), (toPoint, to) ->
                val carrying = fromPoint.carrying && toPoint.carrying
                drawLeg(
                    from = from,
                    to = to,
                    color = if (carrying) toPoint.lamp else colors.screenLand,
                    carrying = carrying && live,
                    phase = phase,
                )
            }

            val placed = mutableListOf<Rect>()
            plotted.forEach { (point, at) ->
                drawHopMark(at, point, colors.screenWell)
                placed += drawHopLabel(
                    at = at,
                    point = point,
                    measurer = measurer,
                    labelStyle = labelStyle.copy(color = point.lamp),
                    occupied = placed,
                )
            }
        }
    }
}

/** Equirectangular: longitude maps straight to x, latitude straight to y. */
private fun DrawScope.project(point: RoutePoint): Offset? {
    if (point.lat == 0.0 && point.lon == 0.0) return null
    return Offset(
        x = ((point.lon + 180.0) / 360.0 * size.width).toFloat(),
        y = ((90.0 - point.lat) / 180.0 * size.height).toFloat(),
    )
}

private fun DrawScope.drawGraticule(color: Color) {
    val faint = color.copy(alpha = 0.5f)
    drawLine(faint, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
    drawLine(faint, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
}

/**
 * A leg bows toward the pole the way a great circle appears to on a flat plate.
 * Packets ride the same curve so the motion follows the drawn path exactly.
 */
private fun DrawScope.drawLeg(
    from: Offset,
    to: Offset,
    color: Color,
    carrying: Boolean,
    phase: Float,
) {
    val control = Offset(
        x = (from.x + to.x) / 2f,
        y = minOf(from.y, to.y) - abs(to.x - from.x) * 0.18f,
    )
    val path = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = if (carrying) 0.55f else 0.85f),
        style = Stroke(
            width = if (carrying) 1.5f else 1f,
            pathEffect = if (carrying) {
                null
            } else {
                PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
            },
        ),
    )
    if (!carrying) return

    repeat(PACKETS_PER_LEG) { index ->
        val t = (phase + index.toFloat() / PACKETS_PER_LEG) % 1f
        val at = quadraticAt(from, control, to, t)
        // Fade in and out at the ends so packets arrive rather than pop.
        val presence = (1f - abs(t - 0.5f) * 2f).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.5f * presence), Color.Transparent),
                center = at,
                radius = 7f,
            ),
            radius = 7f,
            center = at,
        )
        drawCircle(color.copy(alpha = presence), radius = 2f, center = at)
    }
}

private fun quadraticAt(a: Offset, control: Offset, b: Offset, t: Float): Offset {
    val inv = 1f - t
    return Offset(
        x = inv * inv * a.x + 2f * inv * t * control.x + t * t * b.x,
        y = inv * inv * a.y + 2f * inv * t * control.y + t * t * b.y,
    )
}

/** A plotted hop is the same object as a panel lamp, scaled down. */
private fun DrawScope.drawHopMark(at: Offset, point: RoutePoint, wellColor: Color) {
    if (point.carrying) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(point.lamp.copy(alpha = 0.28f), Color.Transparent),
                center = at,
                radius = 13f,
            ),
            radius = 13f,
            center = at,
        )
    }
    drawCircle(wellColor, radius = 4.5f, center = at)
    val fill = if (point.carrying) point.lamp else lerp(point.lamp, wellColor, 0.55f)
    drawCircle(fill, radius = 3f, center = at)
}

/**
 * Returns the rectangle the label ended up in. Hops crowd together — three of
 * them around the North Atlantic, routinely — so a label tries each side of its
 * own marker for a clear spot. It never moves further than that: a label parked
 * somewhere roomier belongs to no dot at all, which is worse than an overlap.
 */
private fun DrawScope.drawHopLabel(
    at: Offset,
    point: RoutePoint,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    occupied: List<Rect>,
): Rect {
    val label = measurer.measure(point.label.uppercase(), labelStyle)
    val w = label.size.width.toFloat()
    val h = label.size.height.toFloat()

    // Ordered by preference, all within a marker's reach: beside, then stacked.
    val candidates = listOf(
        Offset(at.x + LABEL_GAP, at.y - h / 2f),
        Offset(at.x - w - LABEL_GAP, at.y - h / 2f),
        Offset(at.x - w / 2f, at.y - h - LABEL_GAP),
        Offset(at.x - w / 2f, at.y + LABEL_GAP),
        Offset(at.x + LABEL_GAP, at.y + LABEL_GAP),
        Offset(at.x - w - LABEL_GAP, at.y + LABEL_GAP),
    )
    val onPlate = { r: Rect -> r.left >= 0f && r.top >= 0f && r.right <= size.width && r.bottom <= size.height }

    val chosen = candidates.map { Rect(it.x, it.y, it.x + w, it.y + h) }
        .firstOrNull { r -> onPlate(r) && occupied.none { it.overlaps(r) } }
        ?: candidates.map { Rect(it.x, it.y, it.x + w, it.y + h) }.firstOrNull(onPlate)
        ?: Rect(at.x + LABEL_GAP, at.y - h / 2f, at.x + LABEL_GAP + w, at.y + h / 2f)

    drawText(label, topLeft = Offset(chosen.left, chosen.top))
    return chosen
}

private const val PACKETS_PER_LEG = 3
private const val LABEL_GAP = 9f
