package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository

/** Brings the chain up from the stored profile; fails if none is configured. */
class ConnectChainUseCase(
    private val chainRepository: ChainRepository,
) {
    suspend operator fun invoke(): Result<Unit> = chainRepository.connect()
}
