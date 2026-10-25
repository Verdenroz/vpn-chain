package com.verdenroz.vpnchain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.app.generated.resources.Res
import com.verdenroz.vpnchain.app.generated.resources.app_name
import com.verdenroz.vpnchain.core.designsystem.component.Edge
import com.verdenroz.vpnchain.core.designsystem.component.hairline
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NavRail(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val colors = VpnChainTheme.colors
    Column(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(colors.surface)
            .hairline(Edge.Right, colors.line)
            .padding(horizontal = 12.dp, vertical = 20.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(Res.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
            modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
        )
        TopLevelDestination.entries.forEach { destination ->
            val selected = isSelected(destination)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.selected else colors.surface)
                    .selectable(selected, role = Role.Tab) { onSelect(destination) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(destination.label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.ink else colors.inkMuted,
                )
            }
        }
    }
}
