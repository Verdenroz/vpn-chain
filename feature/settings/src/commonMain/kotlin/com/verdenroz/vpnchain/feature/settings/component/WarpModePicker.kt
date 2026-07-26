package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.vpnchain.core.designsystem.component.PanelSelector
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_warp_all
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_warp_blocked
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_warp_off
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How much of the chain's traffic takes the WARP tail — off, some, or all. */
@Composable
fun WarpModePicker(
    selected: WarpMode,
    onSelect: (WarpMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = WarpMode.entries
    PanelSelector(
        options = options.map { stringResource(it.labelRes()) },
        selectedIndex = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
        modifier = modifier,
    )
}

private fun WarpMode.labelRes(): StringResource = when (this) {
    WarpMode.Off -> Res.string.settings_warp_off
    WarpMode.BlockedSites -> Res.string.settings_warp_blocked
    WarpMode.AllTraffic -> Res.string.settings_warp_all
}
