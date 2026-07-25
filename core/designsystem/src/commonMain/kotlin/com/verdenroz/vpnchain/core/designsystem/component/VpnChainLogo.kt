package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The vpn-chain mark: two interlocked links with the three hops — entry,
 * relay, exit — set along the diagonal. Drawn, not shipped as an image, so it
 * renders crisp at any density and can be recolored to suit its surface.
 * Geometry matches assets/branding/logo.svg (authored in a 512-unit space).
 */
@Composable
fun VpnChainLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    colors: List<Color> = BrandGradient,
    contentDescription: String? = null,
) {
    val semantics = contentDescription
        ?.let { Modifier.semantics { this.contentDescription = it } }
        ?: Modifier
    Canvas(modifier.size(size).then(semantics)) {
        val s = this.size.minDimension / 512f
        val brush = Brush.linearGradient(
            colors = colors,
            start = Offset(68f * s, 68f * s),
            end = Offset(444f * s, 444f * s),
        )
        val stroke = Stroke(width = 44f * s)

        fun link(origin: Float) = drawRoundRect(
            brush = brush,
            topLeft = Offset(origin * s, origin * s),
            size = Size(236f * s, 236f * s),
            cornerRadius = CornerRadius(84f * s),
            style = stroke,
        )

        fun hop(center: Float, radius: Float) =
            drawCircle(brush, radius = radius * s, center = Offset(center * s, center * s))

        link(186f) // lower-right link
        link(90f) // upper-left link, laid over it
        // Re-draw the lower link over the bottom-left crossing so the two weave.
        clipRect(140f * s, 280f * s, 232f * s, 372f * s) { link(186f) }

        hop(160f, 18f)
        hop(256f, 26f)
        hop(352f, 18f)
    }
}

/** Brand emerald, dark→light along the weave's diagonal. */
private val BrandGradient = listOf(Color(0xFF047857), Color(0xFF34D399))
