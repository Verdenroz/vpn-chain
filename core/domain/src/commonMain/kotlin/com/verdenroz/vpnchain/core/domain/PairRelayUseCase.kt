package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.relay.AgentException
import com.verdenroz.vpnchain.core.relay.AgentUnreachable
import com.verdenroz.vpnchain.core.relay.DeviceRole
import com.verdenroz.vpnchain.core.relay.ManagementKey
import com.verdenroz.vpnchain.core.relay.PairingException
import com.verdenroz.vpnchain.core.relay.PairingLink
import com.verdenroz.vpnchain.core.relay.RelayAgent
import com.verdenroz.vpnchain.core.relay.id
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class PairingStep { Connecting, Registering, Reconnecting }

/** Why [PairRelayUseCase] stopped. [Reconnecting] failures leave the pairing saved. */
class PairingFailed(
    val step: PairingStep,
    val reason: Reason,
    cause: Throwable? = null,
    /** Whether the link was the first admin code, which decides where a new one comes from. */
    val firstAdmin: Boolean = false,
) : Exception(reason.name, cause) {
    enum class Reason { Expired, Used, Unreachable, Failed }
}

/** The relay a pairing joined, whether this device is its admin, and whether an entry hop it was sent could not be added. */
data class PairedRelay(val host: String, val admin: Boolean, val entryHopFailed: Boolean = false)

interface RelayPairing {
    /** Whether [text] is a pairing link rather than a secrets.env or a WireGuard conf. */
    fun accepts(text: String): Boolean

    /**
     * [onStep] hears each step with the host being paired, which is not a secret.
     */
    suspend fun pair(text: String, onStep: (PairingStep, String) -> Unit): Result<PairedRelay>
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_GONE = 410

/**
 * Turns a pairing link into a connected, enrolled device: dials the relay with the link's
 * identity, registers this device's management key with the one-time token, then reconnects with
 * the rotated identity the relay answers with. The link's identity is dead after that.
 */
class PairRelayUseCase(
    private val profiles: ProfileRepository,
    private val chain: ChainRepository,
    private val settings: SettingsRepository,
    private val agent: RelayAgent,
    private val key: ManagementKey,
    private val nowSeconds: () -> Long,
) : RelayPairing {
    override fun accepts(text: String) = PairingLink.looksLikeLink(text)

    override suspend fun pair(text: String, onStep: (PairingStep, String) -> Unit): Result<PairedRelay> = runCatching {
        val link = PairingLink.parse(text).getOrThrow()
        if (link.isExpired(nowSeconds())) throw PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Expired, firstAdmin = link.firstAdmin)
        val previous = profiles.activeProfileId.first()
        val wasUp = chain.status.first().state == TunnelState.Connected
        val relay = ChainProfile(
            vpsIp = link.host,
            vlessUuid = link.uuid,
            realityPublicKey = link.realityPublicKey,
            shortId = link.shortId,
            sni = link.sni,
            serverPort = link.port,
        )
        val id = profiles.add(name = link.host, relay = relay, wireGuard = null)
        val enrolled = try {
            onStep(PairingStep.Connecting, link.host)
            moveTunnel(wasUp).getOrElse { throw PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Unreachable, it) }
            awaitConnected()
            onStep(PairingStep.Registering, link.host)
            enroll(link)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { undo(id, previous, wasUp) } }
            throw e
        }
        profiles.save(
            relay.copy(vlessUuid = enrolled.identity.uuid, shortId = enrolled.identity.shortId, managementKeyId = key.id, managementAdmin = enrolled.role == DeviceRole.Admin, managementDevice = enrolled.identity.name),
            entryHop = null,
        )
        val entryHop = enrolled.entryHop
        val entryHopFailed = entryHop != null && profiles.importWireGuardConf(entryHop, intoActive = true).isFailure
        if (entryHop != null && !entryHopFailed) settings.setEntryHopEnabled(true)
        onStep(PairingStep.Reconnecting, link.host)
        chain.restart().getOrElse { throw PairingFailed(PairingStep.Reconnecting, PairingFailed.Reason.Failed, it) }
        PairedRelay(link.host, enrolled.role == DeviceRole.Admin, entryHopFailed)
    }.recoverCatching { throw it as? PairingFailed ?: it as? PairingException ?: PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Failed, it) }

    private suspend fun awaitConnected() {
        val settled = chain.status.first { it.state != TunnelState.Connecting }
        if (settled.state != TunnelState.Connected) throw PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Unreachable)
    }

    private suspend fun moveTunnel(wasUp: Boolean) = if (wasUp) chain.restart() else chain.connect()

    private suspend fun enroll(link: PairingLink) = try {
        agent.enroll(link.token, key)
    } catch (e: AgentException) {
        val reason = when (e.status) {
            HTTP_UNAUTHORIZED -> PairingFailed.Reason.Used
            HTTP_GONE -> PairingFailed.Reason.Expired
            else -> PairingFailed.Reason.Failed
        }
        throw PairingFailed(PairingStep.Registering, reason, e, link.firstAdmin)
    } catch (e: AgentUnreachable) {
        throw PairingFailed(PairingStep.Registering, PairingFailed.Reason.Unreachable, e)
    }

    private suspend fun undo(id: String, previous: String?, wasUp: Boolean) {
        profiles.delete(id)
        previous?.let { profiles.setActive(it) }
        if (wasUp && previous != null) chain.restart() else chain.disconnect()
    }
}
