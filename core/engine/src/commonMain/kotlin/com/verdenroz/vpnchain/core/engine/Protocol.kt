package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.model.ConnectionKind
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The control socket's wire format: one JSON object per line. A client opens
 * with [Request.Hello]; either side refuses a [PROTOCOL_VERSION] it doesn't
 * speak. Every reply carries the id of the request it answers, and a
 * [Request.Subscribe] keeps answering under its id until the connection closes.
 */
const val PROTOCOL_VERSION = 1

val engineJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

@Serializable
data class ClientMessage(val id: Long, val request: Request)

@Serializable
data class ServerMessage(val id: Long, val reply: Reply)

@Serializable
sealed interface Request {
    @Serializable
    @SerialName("hello")
    data class Hello(val version: Int) : Request

    @Serializable
    @SerialName("status")
    data object Status : Request

    /** Connects, first switching to [profile] (an id or a name) when given. */
    @Serializable
    @SerialName("up")
    data class Up(val profile: String? = null) : Request

    @Serializable
    @SerialName("down")
    data object Down : Request

    @Serializable
    @SerialName("profiles")
    data object Profiles : Request

    @Serializable
    @SerialName("use")
    data class Use(val profile: String) : Request

    /** The newest [limit] buffered lines, or all of them. */
    @Serializable
    @SerialName("logs")
    data class Logs(val limit: Int? = null) : Request

    @Serializable
    @SerialName("show")
    data object Show : Request

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val topic: Topic) : Request
}

@Serializable
enum class Topic {
    @SerialName("status")
    Status,

    @SerialName("stats")
    Stats,

    /** The whole buffer first, then each batch of new lines. */
    @SerialName("logs")
    Logs,
}

@Serializable
sealed interface Reply {
    @Serializable
    @SerialName("hello")
    data class Hello(val version: Int, val appVersion: String) : Reply

    @Serializable
    @SerialName("ok")
    data object Ok : Reply

    @Serializable
    @SerialName("failure")
    data class Failure(val code: FailureCode, val message: String) : Reply

    @Serializable
    @SerialName("status")
    data class Status(val status: EngineStatus) : Reply

    @Serializable
    @SerialName("stats")
    data class Stats(val stats: EngineStats) : Reply

    @Serializable
    @SerialName("profiles")
    data class Profiles(val profiles: List<ProfileSummary>) : Reply

    @Serializable
    @SerialName("logs")
    data class Logs(val lines: List<LogLine>) : Reply
}

@Serializable
enum class FailureCode {
    @SerialName("version")
    Version,

    @SerialName("bad_request")
    BadRequest,

    @SerialName("not_found")
    NotFound,

    @SerialName("ambiguous")
    Ambiguous,

    @SerialName("failed")
    Failed,
}

/** UI text as it crosses the socket: a string resource key and its arguments, resolved by the client. */
@Serializable
sealed interface WireText {
    @Serializable
    @SerialName("text")
    data class Plain(val text: String) : WireText

    @Serializable
    @SerialName("resource")
    data class Resource(val key: String, val args: List<String> = emptyList()) : WireText
}

@Serializable
data class EngineStatus(
    val state: TunnelState,
    val profile: ProfileSummary? = null,
    val entryHopActive: Boolean = false,
    val killSwitch: KillSwitchState,
    val exitIp: String? = null,
    val detail: WireText? = null,
    /** An [TunnelState.Error] here is not final: the user wants the chain up and the engine will redial. */
    val willRetry: Boolean = false,
)

@Serializable
data class EngineStats(
    val connectedSinceMillis: Long? = null,
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val uplinkBytesPerSecond: Long = 0,
    val downlinkBytesPerSecond: Long = 0,
)

/**
 * What a client may show of a saved connection: never its keys. The name is the
 * user's, and may default to the server's address.
 */
@Serializable
data class ProfileSummary(
    val id: String,
    val name: String,
    val kind: ConnectionKind,
    val active: Boolean,
)

@Serializable
data class LogLine(val timestampMillis: Long, val message: String)
