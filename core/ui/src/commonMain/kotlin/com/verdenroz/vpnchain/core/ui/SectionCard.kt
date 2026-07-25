package com.verdenroz.vpnchain.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.EngravedText
import com.verdenroz.vpnchain.core.designsystem.component.machinedSurface
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme

/**
 * A named group on the panel: the name is silkscreened onto the enclosure with
 * a score line running off it, and the contents sit on a machined face below.
 * Deliberately not a card — the label belongs to the panel, not to a container
 * floating on top of it.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel(title)
        Spacer(Modifier.height(9.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .machinedSurface(PanelTheme.colors)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides PanelTheme.colors.readout,
                content = content,
            )
        }
    }
}

/** The engraved name plus its score line — reusable outside [SectionCard]. */
@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    val colors = PanelTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EngravedText(
            title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = colors.engraved,
        )
        // A cut score line: the groove, then the light gathering on its lower lip.
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .drawBehind {
                    val y = size.height / 2f
                    drawLine(colors.edgeShadow, Offset(0f, y), Offset(size.width, y), 1f)
                    drawLine(
                        colors.edgeHighlight.copy(alpha = 0.4f),
                        Offset(0f, y + 1f),
                        Offset(size.width, y + 1f),
                        1f,
                    )
                },
        )
    }
}
