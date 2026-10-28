package com.verdenroz.vpnchain.core.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceRole {
    @SerialName("admin") Admin,
    @SerialName("member") Member,
}

@Serializable
data class RelayStatus(
    val relayd: String,
    val xray: String,
    val devices: Int,
    /** Spare short IDs left for new devices. */
    val pool: Int,
    /** Whether the relay can run restart and update jobs. */
    val jobs: Boolean,
)

@Serializable
data class AgentDevice(
    val name: String,
    val role: DeviceRole,
    val added: String,
    val up: Long,
    val down: Long,
    val seen: String? = null,
)

/** A device's VLESS identity as the agent hands it out. It is a secret, so [toString] shows the name only. */
@Serializable
data class AgentIdentity(
    val name: String,
    val uuid: String,
    val shortId: String,
    val port: Int,
    val serverNames: List<String>,
) {
    override fun toString() = "AgentIdentity(name=$name)"
}

/** A pairing link the agent issued, valid until unix second [expires]. It is a secret, so [toString] shows the expiry only. */
@Serializable
data class PairingCode(val link: String, val expires: Long) {
    override fun toString() = "PairingCode(expires=$expires)"
}

/** What the agent answers to a pool refill. [job] is set when the xray restart was queued instead of done. */
@Serializable
data class Refilled(val added: Int, val job: String? = null)

private val DEVICE_NAME = Regex("[a-z0-9][a-z0-9._-]{0,31}")

/** Whether relayd accepts [name] for a device. */
fun isDeviceName(name: String) = DEVICE_NAME.matches(name)

/** What the agent answers to a revoke. [job] is set when the short ID's removal needs an xray restart. */
@Serializable
data class Revoked(val countersLost: Boolean, val job: String? = null)

/**
 * What enrolling returns: the device's rotated identity, the id relayd now knows its key by, and its role.
 * [entryHop] is the `wg-quick` text of a WireGuard entry hop the installer was given, which holds a private key.
 */
@Serializable
data class Enrolled(val identity: AgentIdentity, val keyId: String, val role: DeviceRole, val entryHop: String? = null) {
    override fun toString() = "Enrolled(identity=$identity)"
}

/** Where the relay's traffic leaves for the internet. */
sealed interface AgentExit {
    /** The relay's own address. */
    data object Direct : AgentExit

    /** A WireGuard provider, dialled from the relay. */
    data class WireGuard(val endpoint: String) : AgentExit
}

/** The exit to switch to. */
sealed interface ExitChange {
    data object Direct : ExitChange

    /** [conf] is the provider's `wg-quick` file with its private key, so [toString] hides it. */
    data class WireGuard(val conf: String) : ExitChange {
        override fun toString() = "WireGuard(..)"
    }
}

@Serializable
enum class JobState {
    @SerialName("queued") Queued,
    @SerialName("running") Running,
    @SerialName("awaiting-confirm") AwaitingConfirm,
    @SerialName("confirmed") Confirmed,
    @SerialName("rolled-back") RolledBack,
    @SerialName("failed") Failed,
    ;

    val isTerminal get() = this == Confirmed || this == RolledBack || this == Failed
}

@Serializable
data class AgentJob(
    val id: String,
    val kind: String,
    val version: String? = null,
    val state: JobState,
    val reason: String? = null,
    @SerialName("queued_at") val queuedAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    /** While awaiting confirm: the unix second the runner gives up and rolls back. */
    @SerialName("confirm_by") val confirmBy: Long? = null,
)

/** A job a client may ask for. */
sealed interface JobRequest {
    data object RestartXray : JobRequest
    data class SwapXray(val version: String) : JobRequest
}
