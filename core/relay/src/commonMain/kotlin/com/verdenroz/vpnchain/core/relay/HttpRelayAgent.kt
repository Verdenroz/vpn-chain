package com.verdenroz.vpnchain.core.relay

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString

/** A 401 with a clock this far from ours is read as skew, not as a refused key. */
private const val SKEW_THRESHOLD_SECONDS = 30L

private val json = Json { ignoreUnknownKeys = true }

internal class HttpRelayAgent(
    private val key: ManagementKey,
    private val probe: ProbeSource,
    private val transport: AgentTransport,
    private val seconds: () -> Long,
    private val nonce: () -> String = ::newNonce,
) : RelayAgent {

    // Added to the local clock so a device whose clock is wrong still signs a timestamp relayd accepts.
    private var skew = 0L

    override suspend fun status() = get("/v1/relay").decode<RelayStatus>()

    override suspend fun devices() = get("/v1/devices").field<List<AgentDevice>>("devices")

    override suspend fun add(name: String, role: DeviceRole) =
        send("POST", "/v1/devices", buildJsonObject {
            put("name", name)
            put("role", role.name.lowercase())
        }.toString()).field<AgentIdentity>("identity")

    override suspend fun rename(name: String, to: String) {
        send("PATCH", "/v1/devices/${segment(name)}", buildJsonObject { put("name", to) }.toString())
    }

    override suspend fun revoke(name: String) = send("DELETE", "/v1/devices/${segment(name)}").decode<Revoked>()

    override suspend fun rekey(name: String) = send("POST", "/v1/devices/${segment(name)}/rekey").field<AgentIdentity>("identity")

    override suspend fun refillPool() = send("POST", "/v1/pool/refill").decode<Refilled>()

    override suspend fun pair(name: String) = send("POST", "/v1/devices/${segment(name)}/pair").decode<PairingCode>()

    override suspend fun startJob(request: JobRequest): String {
        val body = buildJsonObject {
            when (request) {
                JobRequest.RestartXray -> put("kind", "restart-xray")
                is JobRequest.SwapXray -> {
                    put("kind", "swap-xray")
                    put("version", request.version)
                }
            }
        }
        return send("POST", "/v1/jobs", body.toString()).field("id")
    }

    override suspend fun job(id: String) = get("/v1/jobs/$id").decode<AgentJob>()

    override suspend fun confirm(id: String) {
        send("POST", "/v1/jobs/$id/confirm")
    }

    override suspend fun enroll(token: String, managementKey: ManagementKey): Enrolled {
        val body = buildJsonObject {
            put("token", token)
            put("publicKey", managementKey.publicKey.toByteString().base64())
        }.toString().encodeToByteArray()
        val request = AgentRequest("POST", "/v1/enroll", body, mapOf("Content-Type" to "application/json"))
        return check(transport.send(proxy(), request)).decode<Enrolled>()
    }

    private suspend fun get(target: String) = send("GET", target)

    private suspend fun send(method: String, target: String, body: String = "") =
        check(exchange(method, target, body.encodeToByteArray()))

    private suspend fun exchange(method: String, target: String, body: ByteArray): AgentResponse {
        val response = signedOnce(method, target, body)
        val serverTime = response.serverTime
        if (response.status != 401 || serverTime == null || abs(serverTime - seconds() - skew) <= SKEW_THRESHOLD_SECONDS) {
            return response
        }
        skew = serverTime - seconds()
        return signedOnce(method, target, body)
    }

    private suspend fun signedOnce(method: String, target: String, body: ByteArray): AgentResponse {
        val timestamp = (seconds() + skew).toString()
        val nonce = nonce()
        val signature = key.sign(signedMessage(method, target, body, timestamp, nonce).encodeToByteArray())
        val headers = mapOf(
            "X-Key-Id" to key.id,
            "X-Timestamp" to timestamp,
            "X-Nonce" to nonce,
            "X-Signature" to signature.toByteString().base64(),
            "Content-Type" to "application/json",
        )
        return transport.send(proxy(), AgentRequest(method, target, body, headers))
    }

    // A name goes into the signed request target, so it must not be able to change the path.
    private fun segment(name: String) = name.also { require(isDeviceName(it)) { "not a device name" } }

    private fun proxy() = probe.current() ?: throw AgentUnreachable("The tunnel is not connected.")

    private fun check(response: AgentResponse): AgentResponse {
        if (response.status in 200..299) return response
        val message = runCatching { json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        throw AgentException(response.status, message ?: "The relay answered ${response.status}.")
    }

    private inline fun <reified T> AgentResponse.decode(): T = json.decodeFromString(body)

    private inline fun <reified T> AgentResponse.field(name: String): T =
        json.decodeFromJsonElement(json.parseToJsonElement(body).jsonObject.getValue(name))
}
