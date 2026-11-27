package com.verdenroz.vpnchain.feature.settings

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfFileReadTest {

    private fun read(bytes: ByteArray) = readPickedConf { ByteArrayInputStream(bytes) }

    @Test
    fun `a conf is read as text`() {
        assertEquals(PickedConf.Text("[Interface]\nAddress = 10.2.0.2/32"), read("[Interface]\nAddress = 10.2.0.2/32".toByteArray()))
    }

    @Test
    fun `a leading byte order mark is dropped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[Interface]".toByteArray()

        assertEquals(PickedConf.Text("[Interface]"), read(bytes))
    }

    @Test
    fun `utf-8 outside ascii survives`() {
        assertEquals(PickedConf.Text("# café"), read("# café".toByteArray()))
    }

    @Test
    fun `a file at the cap is read and one byte over is too large`() {
        assertEquals(PickedConf.Text("a".repeat(CONF_MAX_BYTES)), read(ByteArray(CONF_MAX_BYTES) { 'a'.code.toByte() }))
        assertEquals(PickedConf.TooLarge, read(ByteArray(CONF_MAX_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test
    fun `bytes that are not utf-8 are unreadable`() {
        assertEquals(PickedConf.Unreadable, read(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `a stream that cannot be opened or read is unreadable`() {
        assertEquals(PickedConf.Unreadable, readPickedConf { null })
        assertEquals(PickedConf.Unreadable, readPickedConf { throw SecurityException() })
        assertEquals(
            PickedConf.Unreadable,
            readPickedConf { object : InputStream() { override fun read(): Int = throw IOException("gone") } },
        )
    }

    @Test
    fun `printing a picked file hides its text`() {
        assertFalse("secret" in PickedConf.Text("secret").toString())
    }
}
