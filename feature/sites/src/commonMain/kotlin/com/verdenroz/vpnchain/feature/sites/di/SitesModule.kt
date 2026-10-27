package com.verdenroz.vpnchain.feature.sites.di

import com.verdenroz.vpnchain.feature.sites.SitesViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val sitesFeatureModule = module {
    viewModelOf(::SitesViewModel)
}
