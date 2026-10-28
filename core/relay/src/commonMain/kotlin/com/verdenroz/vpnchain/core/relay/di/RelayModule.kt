package com.verdenroz.vpnchain.core.relay.di

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.relay.AgentTransport
import com.verdenroz.vpnchain.core.relay.HttpRelayAgent
import com.verdenroz.vpnchain.core.relay.RelayAgent
import com.verdenroz.vpnchain.core.relay.createAgentTransport
import org.koin.core.module.Module
import org.koin.dsl.module

expect val relayPlatformModule: Module

/** The agent client needs a [com.verdenroz.vpnchain.core.relay.ProbeSource], which the data layer binds. */
val relayModule = module {
    includes(relayPlatformModule)
    single<AgentTransport> { createAgentTransport() }
    single<RelayAgent> {
        HttpRelayAgent(key = get(), probe = get(), transport = get(), seconds = { currentTimeMillis() / 1000 })
    }
}
