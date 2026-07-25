package com.verdenroz.vpnchain.feature.chain.di

import com.verdenroz.vpnchain.feature.chain.ChainViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val chainFeatureModule = module {
    viewModelOf(::ChainViewModel)
}
