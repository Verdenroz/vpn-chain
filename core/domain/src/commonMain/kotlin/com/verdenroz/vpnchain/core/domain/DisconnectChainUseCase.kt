package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository

class DisconnectChainUseCase(
    private val chainRepository: ChainRepository,
) {
    suspend operator fun invoke() = chainRepository.disconnect()
}
