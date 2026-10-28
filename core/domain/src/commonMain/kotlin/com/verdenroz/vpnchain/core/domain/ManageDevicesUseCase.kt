package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.relay.AgentDevice
import com.verdenroz.vpnchain.core.relay.AgentException
import com.verdenroz.vpnchain.core.relay.AgentExit
import com.verdenroz.vpnchain.core.relay.AgentUnreachable
import com.verdenroz.vpnchain.core.relay.ExitChange
import com.verdenroz.vpnchain.core.relay.JobState
import com.verdenroz.vpnchain.core.relay.PairingCode
import com.verdenroz.vpnchain.core.relay.RelayAgent
import com.verdenroz.vpnchain.core.relay.awaitJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Why the relay's devices cannot be shown or changed. [message] is safe to show for [Reason.Refused]. */
class DevicesFailed(val reason: Reason, message: String? = null, cause: Throwable? = null) : Exception(message ?: reason.name, cause) {
    enum class Reason { NoKey, Offline, PoolEmpty, NameTaken, Refused, Failed }
}

/**
 * The relay's devices as this device may see them. [self] is this device's name on the relay,
 * and [poolEmpty] means adding one first needs an xray restart.
 */
data class DeviceBoard(
    val devices: List<AgentDevice>,
    val self: String?,
    val admin: Boolean,
    val poolEmpty: Boolean,
)

/** Where the relay's traffic leaves. [exit] is null for a member, who is not told. */
data class ExitBoard(val admin: Boolean, val exit: AgentExit?)

interface RelayDevices {
    /** Emits when the active relay's key or the tunnel's reachability changes, which is when [load] may answer differently. */
    val changes: Flow<Unit>

    suspend fun load(): Result<DeviceBoard>

    /** Whether [conf] can be another device's entry hop. A failure's message says why, without the conf. */
    suspend fun checkEntryHop(conf: String): Result<Unit>

    /** Adds [name] and issues the code that enrolls it, which also hands it [entryHop] when given one. */
    suspend fun add(name: String, entryHop: String? = null): Result<PairingCode>

    suspend fun rename(name: String, to: String): Result<Unit>

    /** Removes [name] and waits out the xray restart, which drops every device briefly. */
    suspend fun cutOff(name: String): Result<Unit>

    /** A new code for [name], which signs out the app it is already on. */
    suspend fun newCode(name: String): Result<PairingCode>

    /** Tops up the spare short IDs and waits out the xray restart, which drops every device briefly. */
    suspend fun refillPool(): Result<Unit>

    suspend fun loadExit(): Result<ExitBoard>

    /** Switches the exit and waits out the xray restart, which drops every device briefly. */
    suspend fun setExit(change: ExitChange): Result<Unit>
}

private const val HTTP_CONFLICT = 409

class ManageDevicesUseCase(
    private val profiles: ProfileRepository,
    private val chain: ChainRepository,
    private val agent: RelayAgent,
) : RelayDevices {
    override val changes = combine(
        profiles.profile.map { it?.relay?.managementKeyId },
        chain.status.map { it.state == TunnelState.Connected },
        ::Pair,
    ).distinctUntilChanged().map { }

    override suspend fun load() = call {
        val relay = keyed()
        val devices = agent.devices()
        DeviceBoard(devices, relay.managementDevice, relay.managementAdmin, poolEmpty = agent.status().pool == 0)
    }

    override suspend fun checkEntryHop(conf: String) = call {
        val entry = WireGuardConfParser.parse(conf).getOrElse { throw DevicesFailed(DevicesFailed.Reason.Refused, it.message) }
        if (entry.privateKey == profiles.profile.first()?.wireGuard?.privateKey) {
            throw DevicesFailed(DevicesFailed.Reason.Refused, "That is this device's own config. Each device needs its own.")
        }
    }

    override suspend fun add(name: String, entryHop: String?) = call {
        keyed()
        if (agent.status().pool == 0) throw DevicesFailed(DevicesFailed.Reason.PoolEmpty)
        takenIs409 { agent.add(name) }
        agent.pair(name, entryHop)
    }

    override suspend fun rename(name: String, to: String) = call {
        keyed()
        takenIs409 { agent.rename(name, to) }
    }

    override suspend fun cutOff(name: String) = call {
        keyed()
        agent.revoke(name).job?.let { finish(it) }
        Unit
    }

    override suspend fun newCode(name: String) = call {
        keyed()
        agent.pair(name)
    }

    override suspend fun refillPool() = call {
        keyed()
        agent.refillPool().job?.let { finish(it) }
        Unit
    }

    override suspend fun loadExit() = call {
        val relay = keyed()
        ExitBoard(relay.managementAdmin, if (relay.managementAdmin) agent.exit() else null)
    }

    override suspend fun setExit(change: ExitChange) = call {
        keyed()
        agent.setExit(change)?.let { finish(it) }
        Unit
    }

    private suspend fun keyed() =
        profiles.profile.first()?.relay?.takeIf { it.managementKeyId != null } ?: throw DevicesFailed(DevicesFailed.Reason.NoKey)

    private suspend fun takenIs409(request: suspend () -> Unit) {
        try {
            request()
        } catch (e: AgentException) {
            throw if (e.status == HTTP_CONFLICT) DevicesFailed(DevicesFailed.Reason.NameTaken, e.message, e) else e
        }
    }

    /** A restart drops the tunnel, so the job is followed through a reconnect. */
    private suspend fun finish(job: String) {
        val done = agent.awaitJob(job, reconnect = { chain.restart() })
        if (done.state != JobState.Confirmed) throw DevicesFailed(DevicesFailed.Reason.Failed, done.reason)
    }

    private suspend fun <T> call(block: suspend () -> T) =
        runCatching { block() }.recoverCatching {
            throw when (it) {
                is DevicesFailed -> it
                is AgentUnreachable -> DevicesFailed(DevicesFailed.Reason.Offline, cause = it)
                is AgentException -> DevicesFailed(DevicesFailed.Reason.Refused, it.message, it)
                else -> DevicesFailed(DevicesFailed.Reason.Failed, cause = it)
            }
        }
}
