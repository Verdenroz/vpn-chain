package com.verdenroz.vpnchain.core.relay

import com.verdenroz.vpnchain.core.config.ProbeProxy
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_793_000_000L
private val IDENTITY = """{"v":1,"name":"phone","uuid":"22222222-2222-4222-8222-222222222222","shortId":"a1b2c3d4e5f60718","port":443,"serverNames":["www.example.com"]}"""

class HttpRelayAgentTest {

    private val key = fileManagementKey(File.createTempFile("management-key", null).apply { delete() })
    private var relayClock = NOW
    private var localClock = NOW
    private val agents = mutableListOf<FakeAgent>()
    private var route: (Seen, String?) -> Reply = { _, _ -> Reply(200, """{"devices":[]}""") }

    @AfterTest
    fun close() = agents.forEach(FakeAgent::close)

    private fun agent(registered: Map<String, ByteArray> = mapOf(key.id to key.publicKey)): Pair<HttpRelayAgent, FakeAgent> {
        val fake = FakeAgent(registered, { relayClock }) { seen, who -> route(seen, who) }.also(agents::add)
        return HttpRelayAgent(key, { fake.proxy }, createAgentTransport(), { localClock }) to fake
    }

    @Test
    fun `a request is tunnelled to the reserved name with the probe credentials`() = runTest {
        route = { _, _ -> Reply(200, """{"relayd":"0.1.0","xray":"26.3.27","devices":2,"pool":5,"jobs":true}""") }
        val (client, fake) = agent()

        val status = client.status()

        assertEquals(RelayStatus("0.1.0", "26.3.27", 2, 5, true), status)
        val request = fake.seen.single()
        assertEquals("agent.vpn-chain.invalid:80", request.connect)
        assertEquals("agent.vpn-chain.invalid", request.headers["Host"])
        assertEquals("Basic " + Base64.getEncoder().encodeToString("probe:secret".toByteArray()), request.proxyAuth)
    }

    @Test
    fun `the signature covers exactly the target and body that were sent`() = runTest {
        route = { _, who -> Reply(if (who == key.id) 201 else 500, """{"identity":$IDENTITY}""") }
        val (client, fake) = agent()

        val identity = client.add("phone", DeviceRole.Member)

        assertEquals("phone", identity.name)
        val request = fake.seen.single()
        assertEquals("POST /v1/devices", "${request.method} ${request.target}")
        assertEquals("""{"name":"phone","role":"member"}""", request.body)
    }

    @Test
    fun `every request carries a fresh nonce`() = runTest {
        val (client, fake) = agent()

        repeat(3) { client.devices() }

        val nonces = fake.seen.map { it.headers.getValue("X-Nonce") }
        assertEquals(3, nonces.toSet().size)
    }

    @Test
    fun `a request relayd would not accept is refused with its own wording`() = runTest {
        route = { _, _ -> Reply(403, """{"error":"this key's role may not do that"}""") }
        val (client, _) = agent()

        val refused = assertFailsWith<AgentException> { client.revoke("phone") }

        assertEquals(403, refused.status)
        assertEquals("this key's role may not do that", refused.message)
    }

    @Test
    fun `an unknown key gets one attempt, not a retry`() = runTest {
        val (client, fake) = agent(registered = emptyMap())

        assertEquals(401, assertFailsWith<AgentException> { client.status() }.status)
        assertEquals(1, fake.seen.size)
    }

    @Test
    fun `a 401 with a skewed clock resyncs once and the new offset sticks`() = runTest {
        relayClock = NOW + 3_600
        val (client, fake) = agent()

        client.devices()
        client.devices()

        assertEquals(3, fake.seen.size)
        assertEquals((NOW + 3_600).toString(), fake.seen[1].headers["X-Timestamp"])
        assertNotEquals(fake.seen[0].headers["X-Timestamp"], fake.seen[1].headers["X-Timestamp"])
    }

    @Test
    fun `a clock that stays refused after one resync gives up`() = runTest {
        val (client, fake) = agent(registered = emptyMap())
        relayClock = NOW + 3_600

        assertFailsWith<AgentException> { client.status() }
        assertEquals(2, fake.seen.size)
    }

    @Test
    fun `enroll is sent unsigned with the token and the public key`() = runTest {
        route = { _, _ -> Reply(200, """{"identity":$IDENTITY,"keyId":"${key.id}","role":"member"}""") }
        val (client, fake) = agent(registered = emptyMap())

        val enrolled = client.enroll("tokenvalue", key)

        assertEquals(key.id, enrolled.keyId)
        assertEquals(DeviceRole.Member, enrolled.role)
        assertEquals("phone", enrolled.identity.name)
        val request = fake.seen.single()
        assertEquals("/v1/enroll", request.target)
        assertNull(request.headers["X-Signature"])
        assertTrue(""""token":"tokenvalue"""" in request.body)
        val sent = Base64.getDecoder().decode(request.body.substringAfter(""""publicKey":"""").substringBefore('"'))
        assertContentEquals(key.publicKey, sent)
    }

    @Test
    fun `an expired token surfaces as 410`() = runTest {
        route = { _, _ -> Reply(410, """{"error":"expired"}""") }
        val (client, _) = agent(registered = emptyMap())

        assertEquals(410, assertFailsWith<AgentException> { client.enroll("t", key) }.status)
    }

    @Test
    fun `jobs, rekey and revoke decode the agent's answers`() = runTest {
        route = { seen, _ ->
            when {
                seen.target == "/v1/jobs" -> Reply(202, """{"id":"${"a".repeat(32)}"}""")
                seen.target.endsWith("/rekey") -> Reply(200, """{"identity":$IDENTITY}""")
                seen.method == "DELETE" -> Reply(202, """{"countersLost":false,"job":"${"b".repeat(32)}"}""")
                else -> Reply(
                    200,
                    """{"id":"${"a".repeat(32)}","kind":"swap-xray","version":"26.3.27","state":"awaiting-confirm","reason":null,"queued_at":1,"updated_at":2,"confirm_by":3}""",
                )
            }
        }
        val (client, fake) = agent()

        val id = client.startJob(JobRequest.SwapXray("26.3.27"))
        val job = client.job(id)
        client.rekey("phone")
        val revoked = client.revoke("phone")

        assertEquals(JobState.AwaitingConfirm, job.state)
        assertEquals(3L, job.confirmBy)
        assertEquals("b".repeat(32), revoked.job)
        assertEquals("""{"kind":"swap-xray","version":"26.3.27"}""", fake.seen.first().body)
    }

    @Test
    fun `no tunnel and a closed port both read as unreachable`() = runTest {
        val noTunnel = HttpRelayAgent(key, { null }, createAgentTransport(), { localClock })
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val deadPort = HttpRelayAgent(key, { ProbeProxy(closed, 0, 0, "u", "p") }, createAgentTransport(), { localClock })

        assertFailsWith<AgentUnreachable> { noTunnel.status() }
        assertFailsWith<AgentUnreachable> { deadPort.status() }
    }

    @Test
    fun `a device name that could change the path is not sent`() = runTest {
        val (client, fake) = agent()

        assertFailsWith<IllegalArgumentException> { client.revoke("../relay") }
        assertEquals(0, fake.seen.size)
    }

    @Test
    fun `the signed message matches the vector relayd tests pin`() {
        assertEquals(Vector.MESSAGE, signedMessage("POST", "/v1/devices", "{}".encodeToByteArray(), "100", "0123456789abcdef"))
    }

    @Test
    fun `nothing secret reaches toString or an error`() = runTest {
        route = { _, _ -> Reply(200, """{"identity":$IDENTITY,"keyId":"${key.id}","role":"member"}""") }
        val (client, _) = agent(registered = emptyMap())

        val enrolled = client.enroll("tokenvalue", key)
        val shown = enrolled.toString() + enrolled.identity + AgentUnreachable("The relay did not answer.").message

        for (secret in listOf("22222222-2222-4222-8222-222222222222", "a1b2c3d4e5f60718", "tokenvalue")) {
            assertFalse(secret in shown, secret)
        }
    }
}
