package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/** Why an exit was refused. */
@Serializable
enum class BlockReason { Forbidden, RateLimited, LegalBlock, NetworkSecurity }
