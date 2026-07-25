package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile

actual fun renderPlatformTunnelConfig(profile: ChainProfile, systemWide: Boolean): String =
    if (systemWide) {
        SingBoxConfigFactory.androidChainConfig(profile)
    } else {
        SingBoxConfigFactory.mixedProxyConfig(profile)
    }
