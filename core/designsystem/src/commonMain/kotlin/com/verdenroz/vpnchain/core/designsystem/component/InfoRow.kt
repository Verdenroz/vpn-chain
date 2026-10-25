package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme

/** A hop's dot: hollow for the near end of the chain, solid for where traffic exits. */
data class RowMarker(val color: Color, val filled: Boolean)

/**
 * One labelled line under a hairline. [note] is secondary text after the value,
 * for how the value was learned.
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    marker: RowMarker? = null,
    note: String? = null,
) {
    val colors = VpnChainTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .drawBehind { drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marker != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .then(
                        if (marker.filled) Modifier.background(marker.color, CircleShape)
                        else Modifier.border(2.dp, marker.color, CircleShape),
                    ),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (marker != null) colors.ink else colors.inkMuted,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (marker != null) colors.inkMuted else colors.ink,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
