package com.verdenroz.vpnchain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.Edge
import com.verdenroz.vpnchain.core.designsystem.component.hairline
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BottomTabs(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val colors = VpnChainTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .hairline(Edge.Top, colors.line)
            .navigationBarsPadding()
            .selectableGroup(),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = isSelected(destination)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(selected, role = Role.Tab) { onSelect(destination) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(destination.label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.ink else colors.inkMuted,
                )
            }
        }
    }
}
