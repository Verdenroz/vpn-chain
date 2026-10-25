package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpExit

// Android is always TUN; the systemWide flag is desktop-only. libbox gives
// sing-box a working directory of its own, so the rule-set cache needs no
// explicit path here.
actual fun renderPlatformTunnelConfig(
    profile: SavedProfile,
    settings: UserSettings,
    warp: WarpExit?,
): String {
    val relay = profile.relay
        ?: return SingBoxConfigFactory.wireGuardConfig(
            entry = requireNotNull(profile.wireGuard),
            dnsFilter = settings.dnsFilter,
        )
    return SingBoxConfigFactory.androidChainConfig(
        profile = relay,
        entry = profile.wireGuard,
        dnsFilter = settings.dnsFilter,
        warp = warp,
        warpMode = settings.warpMode,
        warpDomains = settings.warpDomains,
    )
}
