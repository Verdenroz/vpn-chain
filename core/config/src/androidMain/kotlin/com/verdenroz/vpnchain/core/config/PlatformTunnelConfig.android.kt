package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile

// Android is always TUN; the systemWide flag is desktop-only.
actual fun renderPlatformTunnelConfig(profile: ChainProfile, systemWide: Boolean): String =
    SingBoxConfigFactory.androidChainConfig(profile)
