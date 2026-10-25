package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** [hue] in degrees. Out-of-gamut values clamp to the nearest sRGB colour. */
internal fun oklch(lightness: Float, chroma: Float, hue: Float): Color {
    val radians = hue * PI.toFloat() / 180f
    return Color(lightness, chroma * cos(radians), chroma * sin(radians), 1f, ColorSpaces.Oklab)
        .convert(ColorSpaces.Srgb)
}
