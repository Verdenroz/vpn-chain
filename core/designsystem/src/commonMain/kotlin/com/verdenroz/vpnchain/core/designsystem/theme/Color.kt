package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// The enclosure is one anodized material: every shell tone is the same warm
// neutral hue (OKLCH H=85) separated only by lightness, the way a real panel
// reads as one billet catching light differently at each edge. The inset
// display is deliberately a *different* material (H=155), so the screen looks
// set into the panel rather than painted onto it.
//
// Values generated in OKLCH and contrast-checked against their own surface;
// see the comment on each for its source coordinates.

// ── Dark: anodized graphite ────────────────────────────────────────────────
internal val ShellDeepDark = Color(0xFF0C0A07) // oklch(0.145 0.008 85) — window ground, deepest recess
internal val ShellFaceDark = Color(0xFF1E1B17) // oklch(0.225 0.010 85) — panel face
internal val ShellRaisedDark = Color(0xFF2A2722) // oklch(0.275 0.011 85) — raised bezel band
internal val ShellEdgeHiDark = Color(0xFF4B4740) // oklch(0.400 0.012 85) — machined top edge
internal val ShellEdgeLoDark = Color(0xFF040302) // oklch(0.100 0.006 85) — seam shadow
internal val ScreenWellDark = Color(0xFF0B110D) // oklch(0.170 0.014 155) — display backlight ground
internal val ScreenLandDark = Color(0xFF2E3831) // oklch(0.330 0.018 155) — landmass on the display
internal val TextReadoutDark = Color(0xFFEAE8E3) // oklch(0.930 0.006 85) — 14.0:1 on face
internal val TextEngravedDark = Color(0xFFA7A49F) // oklch(0.720 0.008 85) — 6.9:1
internal val TextMutedDark = Color(0xFF868480) // oklch(0.615 0.007 85) — 4.6:1

// ── Light: the same object in a pale alloy ─────────────────────────────────
internal val ShellDeepLight = Color(0xFFBFBDB9) // oklch(0.800 0.006 85)
internal val ShellFaceLight = Color(0xFFD7D6D2) // oklch(0.875 0.005 85)
internal val ShellRaisedLight = Color(0xFFE8E6E2) // oklch(0.925 0.005 85)
internal val ShellEdgeHiLight = Color(0xFFFBFAF8) // oklch(0.985 0.003 85)
internal val ShellEdgeLoLight = Color(0xFF93908B) // oklch(0.655 0.008 85)
internal val ScreenWellLight = Color(0xFFCDD5CF) // oklch(0.865 0.012 155)
// Darker than a straight translation of the dark plate: on a backlit well the
// land reads by being lighter than its ground, on a pale one only by being
// darker, and 1.5:1 was too faint to find a coastline in.
internal val ScreenLandLight = Color(0xFF84988A) // oklch(0.660 0.030 155) — 2.05:1 on the well
internal val TextReadoutLight = Color(0xFF1D1A15) // oklch(0.220 0.010 85) — 11.9:1 on face
internal val TextEngravedLight = Color(0xFF413E39) // oklch(0.365 0.010 85) — 7.3:1
internal val TextMutedLight = Color(0xFF65635E) // oklch(0.500 0.008 85) — 4.1:1

// ── Indicator lamps ───────────────────────────────────────────────────────
// Phosphor colors, not brand colors: a yellow-cast GaP green, a sodium amber,
// an oxide red, and a warm white pilot. Chroma stays moderate — a lit lamp
// reads by being brighter than the panel, not by being more saturated.
internal val LampGreenDark = Color(0xFF80D677) // oklch(0.800 0.155 142)
internal val LampAmberDark = Color(0xFFFAAB35) // oklch(0.800 0.155 72)
internal val LampRedDark = Color(0xFFEB4F3D) // oklch(0.640 0.195 30)
internal val LampWhiteDark = Color(0xFFE4DDCF) // oklch(0.900 0.020 85)

internal val LampGreenLight = Color(0xFF196912) // oklch(0.455 0.140 142) — 4.7:1
internal val LampAmberLight = Color(0xFF8D5200) // oklch(0.495 0.128 72) — 4.3:1
internal val LampRedLight = Color(0xFFAE170A) // oklch(0.480 0.185 30) — 4.9:1
internal val LampWhiteLight = Color(0xFF4B4740) // oklch(0.400 0.012 85)
