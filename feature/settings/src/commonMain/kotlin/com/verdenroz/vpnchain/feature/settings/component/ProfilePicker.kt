package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.IndicatorLamp
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.component.machinedSurface
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_delete
import org.jetbrains.compose.resources.stringResource

/**
 * The saved chains, one per row, with a lit lamp on the active one.
 *
 * Selection is the whole row rather than a control at its edge: picking a
 * profile is the common action here, deleting one is not.
 */
@Composable
internal fun ProfilePicker(
    profiles: List<SavedProfile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    Column(modifier.fillMaxWidth()) {
        profiles.forEachIndexed { index, saved ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            val active = saved.id == activeProfileId
            Row(
                Modifier
                    .fillMaxWidth()
                    .machinedSurface(
                        colors,
                        corner = 2.dp,
                        recessed = !active,
                        fill = if (active) colors.shellRaised else colors.shellDeep,
                    )
                    .clickable(enabled = !active) { onSelect(saved.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IndicatorLamp(color = colors.lampGreen, lit = active, size = 7.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        saved.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) colors.readout else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        saved.profile.vpsIp,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
                // Deleting the last profile is what "clear" is for; keeping one
                // row undeletable stops the list from silently emptying itself.
                if (profiles.size > 1) {
                    Spacer(Modifier.width(10.dp))
                    PanelButton(
                        text = stringResource(Res.string.settings_profile_delete),
                        onClick = { onDelete(saved.id) },
                    )
                }
            }
        }
    }
}
