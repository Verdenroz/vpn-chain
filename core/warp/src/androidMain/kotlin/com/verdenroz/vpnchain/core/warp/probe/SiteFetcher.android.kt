package com.verdenroz.vpnchain.core.warp.probe

actual fun createSiteFetcher(): SiteFetcher = JvmSiteFetcher()
