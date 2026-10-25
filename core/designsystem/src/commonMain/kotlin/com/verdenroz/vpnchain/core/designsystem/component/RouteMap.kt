package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.generated.resources.Res
import com.verdenroz.vpnchain.core.designsystem.generated.resources.world_landmass
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class NodeKind { Origin, Entry, Exit }

data class MapNode(
    val label: String,
    val lat: Double,
    val lon: Double,
    val kind: NodeKind,
)

/**
 * The route over a simplified world map, fitted to its hops. Nothing animates
 * while idle: the view eases only when the hops or the connection change.
 */
@Composable
fun RouteMap(
    nodes: List<MapNode>,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = VpnChainTheme.colors
    val measurer = rememberTextMeasurer()
    val landmass = painterResource(Res.drawable.world_landmass)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = colors.mapLabel)
    val exitLabelStyle = labelStyle.copy(color = colors.ink, fontWeight = FontWeight.SemiBold)
    val routeColor by animateColorAsState(
        if (connected) colors.route else colors.idleRoute,
        label = "route-color",
    )

    BoxWithConstraints(modifier) {
        val camera = fit(nodes, aspect = maxWidth / maxHeight)
        val spec = tween<Float>(CAMERA_MS)
        val centerLon by animateFloatAsState(camera.centerLon, spec, label = "camera-lon")
        val centerLat by animateFloatAsState(camera.centerLat, spec, label = "camera-lat")
        val spanLon by animateFloatAsState(camera.spanLon, spec, label = "camera-span")

        Canvas(Modifier.fillMaxSize()) {
            val pxPerDegree = size.width / spanLon
            fun project(node: MapNode) = Offset(
                x = (node.lon.toFloat() - centerLon) * pxPerDegree + size.width / 2f,
                y = (centerLat - node.lat.toFloat()) * pxPerDegree + size.height / 2f,
            )

            clipRect {
                val worldTopLeft = Offset(
                    x = (-180f - centerLon) * pxPerDegree + size.width / 2f,
                    y = (centerLat - 90f) * pxPerDegree + size.height / 2f,
                )
                clipRect(top = worldTopLeft.y + (90f - NORTH) * pxPerDegree, bottom = worldTopLeft.y + (90f - SOUTH) * pxPerDegree) {
                    translate(worldTopLeft.x, worldTopLeft.y) {
                        with(landmass) {
                            draw(Size(360f * pxPerDegree, 180f * pxPerDegree), colorFilter = ColorFilter.tint(colors.land))
                        }
                    }
                }

                val points = nodes.map { it to project(it) }
                points.zipWithNext { (_, from), (to, at) ->
                    val towardExit = to.kind == NodeKind.Exit
                    drawLeg(
                        from = from,
                        to = at,
                        color = if (towardExit) routeColor else colors.ring,
                        dashed = towardExit && !connected,
                        width = if (towardExit) 2.5.dp.toPx() else 2.dp.toPx(),
                    )
                }

                val placed = mutableListOf<Rect>()
                val named = mutableSetOf<String>()
                points.forEach { (node, at) ->
                    if (node.kind == NodeKind.Exit) {
                        drawCircle(routeColor.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = at)
                        drawCircle(routeColor, radius = 7.dp.toPx(), center = at)
                    } else {
                        drawCircle(colors.ink, radius = 5.dp.toPx(), center = at)
                    }
                    if (named.add(node.label)) {
                        val style = if (node.kind == NodeKind.Exit) exitLabelStyle else labelStyle
                        placed += drawLabel(node.label, at, measurer, style, placed)
                    }
                }
            }
        }
    }
}

private class Camera(val centerLon: Float, val centerLat: Float, val spanLon: Float)

private fun fit(nodes: List<MapNode>, aspect: Float): Camera {
    if (nodes.isEmpty()) return Camera(0f, LAT_MID, MAX_SPAN)
    val lons = nodes.map { it.lon.toFloat() }
    val lats = nodes.map { it.lat.toFloat() }
    val needLon = (lons.max() - lons.min()) / (1f - 2f * SIDE_PAD)
    val needLat = (lats.max() - lats.min()) / (1f - 2f * VERTICAL_PAD)
    val spanLon = max(needLon, needLat * aspect).coerceIn(MIN_SPAN, MAX_SPAN)
    val halfLat = spanLon / aspect / 2f
    val centerLat = (lats.max() + lats.min()) / 2f
    return Camera(
        centerLon = (lons.max() + lons.min()) / 2f,
        centerLat = if (halfLat >= LAT_RANGE / 2f) LAT_MID else centerLat.coerceIn(SOUTH + halfLat, NORTH - halfLat),
        spanLon = spanLon,
    )
}

/** Bowed toward the pole, the way a great circle looks on a flat map. */
private fun DrawScope.drawLeg(from: Offset, to: Offset, color: Color, dashed: Boolean, width: Float) {
    val control = Offset(
        x = (from.x + to.x) / 2f,
        y = min(from.y, to.y) - abs(to.x - from.x) * 0.25f,
    )
    val path = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = width,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())) else null,
        ),
    )
}

private fun DrawScope.drawLabel(
    text: String,
    at: Offset,
    measurer: TextMeasurer,
    style: TextStyle,
    occupied: List<Rect>,
): Rect {
    val layout = measurer.measure(text, style)
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val gap = 10.dp.toPx()
    val candidates = listOf(
        Offset(at.x - w / 2f, at.y + gap),
        Offset(at.x - w / 2f, at.y - h - gap),
        Offset(at.x + gap, at.y - h / 2f),
        Offset(at.x - w - gap, at.y - h / 2f),
    ).map { Rect(it.x, it.y, it.x + w, it.y + h) }
    val chosen = candidates.firstOrNull { r -> occupied.none { it.overlaps(r) } } ?: candidates.first()
    drawText(layout, topLeft = chosen.topLeft)
    return chosen
}

private const val CAMERA_MS = 450
private const val SIDE_PAD = 0.18f
private const val VERTICAL_PAD = 0.28f
private const val MIN_SPAN = 50f
private const val MAX_SPAN = 360f

// Inhabited latitudes; the polar caps are cropped so the map has no hard bottom edge.
private const val NORTH = 84f
private const val SOUTH = -58f
private const val LAT_RANGE = NORTH - SOUTH
private const val LAT_MID = (NORTH + SOUTH) / 2f
