package com.verdenroz.vpnchain.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider

/**
 * A circular icon key at a fixed size, so the shape never depends on the
 * launcher's cell dimensions. The whole widget area remains the tap target.
 */
@Composable
internal fun GlanceIconKey(
    icon: ImageProvider,
    tint: ColorProvider,
    contentDescription: String,
    action: Action,
    modifier: GlanceModifier = GlanceModifier,
) {
    Box(modifier = modifier.clickable(action), contentAlignment = Alignment.Center) {
        Box(
            modifier = GlanceModifier
                .size(KEY_SIZE)
                .background(WidgetPalette.outline)
                .cornerRadius(KEY_SIZE / 2)
                .padding(2.dp),
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetPalette.shellFace)
                    .cornerRadius((KEY_SIZE / 2) - 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = icon,
                    contentDescription = contentDescription,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = GlanceModifier.size(28.dp),
                )
            }
        }
    }
}

internal val KEY_SIZE = 60.dp
