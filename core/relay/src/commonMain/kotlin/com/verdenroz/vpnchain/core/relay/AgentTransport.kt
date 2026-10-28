package com.verdenroz.vpnchain.core.relay

import com.verdenroz.vpnchain.core.config.ProbeProxy

/** The reserved name xray routes to the agent for any client that authenticated to REALITY. */
const val AGENT_HOST = "agent.vpn-chain.invalid"
const val AGENT_PORT = 80

class AgentRequest(
    val method: String,
    /** Path and query, written to the request line exactly as given. */
    val target: String,
    val body: ByteArray,
    val headers: Map<String, String>,
)

class AgentResponse(
    val status: Int,
    val body: String,
    /** The server's clock from its Date header, in unix seconds. */
    val serverTime: Long?,
)

/** Sends one HTTP/1.1 request to [AGENT_HOST] through the relay probe inbound. */
fun interface AgentTransport {
    /** @throws AgentUnreachable when no response arrives. */
    suspend fun send(proxy: ProbeProxy, request: AgentRequest): AgentResponse
}

/** The running tunnel's relay probe, or null while there is no tunnel. */
fun interface ProbeSource {
    fun current(): ProbeProxy?
}

class AgentUnreachable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The agent answered with a refusal. [message] is relayd's own wording, which never holds a secret. */
class AgentException(val status: Int, message: String) : Exception(message)

expect fun createAgentTransport(): AgentTransport

expect fun newNonce(): String

/**
 * Whether the relay's port completes a TLS handshake. Any client gets one, an unknown REALITY
 * identity included, because the relay hands it to the decoy site.
 */
fun interface RelayHandshake {
    suspend fun completes(host: String, port: Int, sni: String): Boolean
}

expect fun createRelayHandshake(): RelayHandshake
