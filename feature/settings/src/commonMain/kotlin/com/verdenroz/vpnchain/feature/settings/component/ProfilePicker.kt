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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.IndicatorLamp
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.component.PanelField
import com.verdenroz.vpnchain.core.designsystem.component.machinedSurface
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_cancel
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_delete
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_name
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_rename
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_rename_save
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
    onRename: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    var renaming by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        profiles.forEachIndexed { index, saved ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            if (renaming == saved.id) {
                RenameRow(
                    initial = saved.name,
                    onSave = { name ->
                        onRename(saved.id, name)
                        renaming = null
                    },
                    onCancel = { renaming = null },
                )
                return@forEachIndexed
            }
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
                        saved.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(10.dp))
                PanelButton(
                    text = stringResource(Res.string.settings_profile_rename),
                    onClick = { renaming = saved.id },
                )
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

@Composable
private fun RenameRow(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth()) {
        PanelField(
            label = stringResource(Res.string.settings_profile_name),
            value = name,
            onValueChange = { name = it },
        )
        Spacer(Modifier.height(8.dp))
        Row {
            PanelButton(
                text = stringResource(Res.string.settings_profile_rename_save),
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
                prominent = true,
            )
            Spacer(Modifier.width(10.dp))
            PanelButton(text = stringResource(Res.string.settings_import_cancel), onClick = onCancel)
        }
    }
}
