package com.verdenroz.vpnchain.core.relay

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object Vector {
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHhhTL9R1TALzBB2cdc6zO4P/2BrHzk/ogsyxyYvFiW6kbDEcTi/0DdlqNlPm5FRF0y3+SG7O11x6kMahiIHAow=="
    const val KEY_ID = "ce97ec20d43661a1"
    const val BODY_DIGEST = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    const val MESSAGE =
        "vpn-chain-relay-v1\nPOST\n/v1/devices\n$BODY_DIGEST\n100\n0123456789abcdef"
    const val SIGNATURE =
        "MEUCID8ZqX2cuI1CQZgV66BrUI2xBbdOiUnRDoYrBQh6gAeCAiEA51WhLrpBzdrr3831Pk6juwmtaoxOKbqukNm7ucAEXVA="
}

class ManagementKeyTest {

    private fun verify(publicKey: ByteArray, message: String, signature: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey)))
            update(message.encodeToByteArray())
            verify(signature)
        }

    private val vectorKey = object : ManagementKey {
        override val publicKey: ByteArray = Base64.getDecoder().decode(Vector.PUBLIC_KEY)
        override suspend fun sign(message: ByteArray) = error("the vector key only verifies")
    }

    @Test
    fun `the key id is the first eight bytes of the public key hash`() {
        assertEquals(Vector.KEY_ID, vectorKey.id)
    }

    @Test
    fun `the shared vector signature verifies on the jvm`() {
        val signature = Base64.getDecoder().decode(Vector.SIGNATURE)

        assertTrue(verify(vectorKey.publicKey, Vector.MESSAGE, signature))
        assertFalse(verify(vectorKey.publicKey, Vector.MESSAGE.replace("POST", "GET"), signature))
    }

    @Test
    fun `a signature from a file key verifies against its public key`() = runTest {
        val key = fileManagementKey(File.createTempFile("management-key", null).apply { delete() })

        val signature = key.sign(Vector.MESSAGE.encodeToByteArray())

        assertTrue(verify(key.publicKey, Vector.MESSAGE, signature))
        assertEquals(0x30, signature[0].toInt())
    }

    @Test
    fun `a file key is created owner-only and read back unchanged`() {
        val file = File.createTempFile("management-key", null).apply { delete() }

        val first = fileManagementKey(file)
        val second = fileManagementKey(file)

        assertContentEquals(first.publicKey, second.publicKey)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }
}
