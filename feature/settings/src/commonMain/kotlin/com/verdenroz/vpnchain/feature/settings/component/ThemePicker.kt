package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.vpnchain.core.designsystem.component.PanelSelector
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_theme_dark
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_theme_light
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_theme_system
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Three stops on one selector — the panel has no second idiom for a choice. */
@Composable
fun ThemePicker(
    selected: ThemeConfig,
    onSelect: (ThemeConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = ThemeConfig.entries
    PanelSelector(
        options = options.map { stringResource(it.labelRes()) },
        selectedIndex = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
        modifier = modifier,
    )
}

private fun ThemeConfig.labelRes(): StringResource = when (this) {
    ThemeConfig.FOLLOW_SYSTEM -> Res.string.settings_theme_system
    ThemeConfig.LIGHT -> Res.string.settings_theme_light
    ThemeConfig.DARK -> Res.string.settings_theme_dark
}
