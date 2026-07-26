package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.vpnchain.core.designsystem.component.PanelSelector
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_dns_filter_ads
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_dns_filter_malware
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_dns_filter_off
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** NetShield's two levels and off, as three stops on the panel's one selector. */
@Composable
fun DnsFilterPicker(
    selected: DnsFilter,
    onSelect: (DnsFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = DnsFilter.entries
    PanelSelector(
        options = options.map { stringResource(it.labelRes()) },
        selectedIndex = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
        modifier = modifier,
    )
}

private fun DnsFilter.labelRes(): StringResource = when (this) {
    DnsFilter.Off -> Res.string.settings_dns_filter_off
    DnsFilter.Malware -> Res.string.settings_dns_filter_malware
    DnsFilter.AdsAndTrackers -> Res.string.settings_dns_filter_ads
}
