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
    learned: LearnedRuleSetStore?,
): String {
    val relay = profile.relay
        ?: return SingBoxConfigFactory.wireGuardConfig(
            entry = requireNotNull(profile.wireGuard),
            dnsFilter = settings.dnsFilter,
            connectionLog = settings.connectionLog,
        )
    return SingBoxConfigFactory.androidChainConfig(
        profile = relay,
        entry = profile.wireGuard,
        dnsFilter = settings.dnsFilter,
        warp = warp,
        warpMode = settings.warpMode,
        probe = newProbeProxy(),
        learnedWarpPath = learned?.path(RuleSetKind.Warp),
        learnedOpenPath = learned?.path(RuleSetKind.Open),
        learnedDirectPath = learned?.path(RuleSetKind.Direct),
        failOpen = settings.failOpen,
        connectionLog = settings.connectionLog,
    )
}
