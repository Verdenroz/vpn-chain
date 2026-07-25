package com.verdenroz.vpnchain.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider

/**
 * PanelTheme's light/dark tokens as day/night providers — widgets can't read
 * the Compose theme, so both alloys are restated here from theme/Color.kt.
 */
internal object WidgetPalette {
    val shellFace = ColorProvider(day = Color(0xFFD7D6D2), night = Color(0xFF1E1B17))
    // Machined edge tones doubling as key outline and divider.
    val outline = ColorProvider(day = Color(0xFF93908B), night = Color(0xFF4B4740))
    val engraved = ColorProvider(day = Color(0xFF413E39), night = Color(0xFFA7A49F))
    val green = ColorProvider(day = Color(0xFF196912), night = Color(0xFF80D677))
    val amber = ColorProvider(day = Color(0xFF8D5200), night = Color(0xFFFAAB35))
    val red = ColorProvider(day = Color(0xFFAE170A), night = Color(0xFFEB4F3D))
}
