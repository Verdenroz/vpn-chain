package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.UserSettings

// Android is always TUN; the systemWide flag is desktop-only. libbox gives
// sing-box a working directory of its own, so the rule-set cache needs no
// explicit path here.
actual fun renderPlatformTunnelConfig(profile: ChainProfile, settings: UserSettings): String =
    SingBoxConfigFactory.androidChainConfig(profile, dnsFilter = settings.dnsFilter)
