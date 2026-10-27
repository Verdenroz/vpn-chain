package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

private const val NEUTRAL_HUE = 250f
private const val GREEN_HUE = 150f
private const val RED_HUE = 25f
private const val AMBER_HUE = 65f

/**
 * [surface] is the card, side panel and nav rail; [land] the map's continents.
 * [connected] and [unprotected] fill the status bar and the primary button;
 * their `Text` twins (and [reroutedText]) are the same hues lightened (dark) or
 * deepened (light) to read as text on [surface]. [route] is the drawn connected leg.
 * [rerouted] is light in both themes, so text on it is dark ([onRerouted]).
 */
@Immutable
data class VpnChainColors(
    val background: Color,
    val surface: Color,
    val land: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val connected: Color,
    val connectedText: Color,
    val route: Color,
    val unprotected: Color,
    val unprotectedText: Color,
    val connecting: Color,
    val disconnect: Color,
    val rerouted: Color,
    val reroutedText: Color,
    val onFilled: Color,
    val onRerouted: Color,
    val outline: Color,
    val ring: Color,
    val selected: Color,
    val idleRoute: Color,
    val mapLabel: Color,
    val isDark: Boolean,
)

val DarkVpnChainColors = VpnChainColors(
    background = oklch(0.25f, 0.04f, NEUTRAL_HUE),
    surface = oklch(0.21f, 0.04f, NEUTRAL_HUE),
    land = oklch(0.31f, 0.04f, NEUTRAL_HUE),
    line = oklch(0.31f, 0.03f, NEUTRAL_HUE),
    ink = oklch(0.96f, 0.005f, NEUTRAL_HUE),
    inkMuted = oklch(0.77f, 0.02f, NEUTRAL_HUE),
    connected = oklch(0.53f, 0.15f, GREEN_HUE),
    connectedText = oklch(0.78f, 0.14f, GREEN_HUE),
    route = oklch(0.78f, 0.14f, GREEN_HUE),
    unprotected = oklch(0.57f, 0.17f, RED_HUE),
    unprotectedText = oklch(0.75f, 0.13f, RED_HUE),
    connecting = oklch(0.36f, 0.03f, NEUTRAL_HUE),
    disconnect = oklch(0.50f, 0.15f, RED_HUE),
    rerouted = oklch(0.80f, 0.13f, AMBER_HUE),
    reroutedText = oklch(0.80f, 0.13f, AMBER_HUE),
    onFilled = oklch(0.98f, 0.005f, NEUTRAL_HUE),
    onRerouted = oklch(0.21f, 0.04f, NEUTRAL_HUE),
    outline = oklch(0.40f, 0.03f, NEUTRAL_HUE),
    ring = oklch(0.80f, 0.03f, NEUTRAL_HUE),
    selected = oklch(0.30f, 0.04f, NEUTRAL_HUE),
    idleRoute = oklch(0.60f, 0.02f, NEUTRAL_HUE),
    mapLabel = oklch(0.80f, 0.02f, NEUTRAL_HUE),
    isDark = true,
)

val LightVpnChainColors = VpnChainColors(
    background = oklch(0.94f, 0.012f, NEUTRAL_HUE),
    surface = oklch(0.995f, 0.002f, NEUTRAL_HUE),
    land = oklch(0.985f, 0.004f, NEUTRAL_HUE),
    line = oklch(0.91f, 0.01f, NEUTRAL_HUE),
    ink = oklch(0.24f, 0.03f, NEUTRAL_HUE),
    inkMuted = oklch(0.46f, 0.02f, NEUTRAL_HUE),
    connected = oklch(0.54f, 0.14f, GREEN_HUE),
    connectedText = oklch(0.46f, 0.13f, GREEN_HUE),
    route = oklch(0.56f, 0.14f, GREEN_HUE),
    unprotected = oklch(0.52f, 0.16f, RED_HUE),
    unprotectedText = oklch(0.46f, 0.16f, RED_HUE),
    connecting = oklch(0.50f, 0.03f, NEUTRAL_HUE),
    disconnect = oklch(0.52f, 0.16f, RED_HUE),
    rerouted = oklch(0.66f, 0.13f, AMBER_HUE),
    reroutedText = oklch(0.52f, 0.12f, AMBER_HUE),
    onFilled = oklch(0.99f, 0.004f, NEUTRAL_HUE),
    onRerouted = oklch(0.24f, 0.03f, NEUTRAL_HUE),
    outline = oklch(0.85f, 0.01f, NEUTRAL_HUE),
    ring = oklch(0.40f, 0.03f, NEUTRAL_HUE),
    selected = oklch(0.90f, 0.014f, NEUTRAL_HUE),
    idleRoute = oklch(0.62f, 0.02f, NEUTRAL_HUE),
    mapLabel = oklch(0.46f, 0.02f, NEUTRAL_HUE),
    isDark = false,
)

/** Only the slots Material components read here: selection handles, disabled switches, snackbars. */
internal fun VpnChainColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = connected,
        onPrimary = onFilled,
        background = background,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        inverseSurface = ink,
        inverseOnSurface = surface,
    )
}
