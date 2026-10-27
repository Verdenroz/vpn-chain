package com.verdenroz.vpnchain.core.relay

import okio.Buffer

/** Why a pairing link was refused. The text is safe to show: it never repeats the link. */
class PairingException(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { NotALink, UnsupportedVersion, Invalid }
}

/**
 * The one-time link the installer prints, `vpnchain://pair?v=1&h=...`. It carries a VLESS
 * identity and an enroll token, so it is a secret: [toString] shows the host and nothing else.
 */
data class PairingLink(
    val host: String,
    val port: Int,
    val sni: String,
    val realityPublicKey: String,
    val shortId: String,
    val uuid: String,
    val deviceName: String,
    val token: String,
    val expires: Long,
) {
    fun isExpired(nowSeconds: Long) = nowSeconds >= expires

    fun format(): String = buildString {
        append(PREFIX).append("v=").append(VERSION)
        append("&h=").append(encode(if (':' in host) "[$host]" else host))
        append("&p=").append(port)
        append("&sni=").append(encode(sni))
        append("&pbk=").append(encode(realityPublicKey))
        append("&sid=").append(encode(shortId))
        append("&id=").append(encode(uuid))
        append("&n=").append(encode(deviceName))
        append("&t=").append(encode(token))
        append("&e=").append(expires)
    }

    override fun toString() = "PairingLink(host=$host)"

    companion object {
        private const val PREFIX = "vpnchain://pair?"
        private const val VERSION = "1"
        private const val KEY_LENGTH = 43
        private val ipv4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
        private val ipv6 = Regex("""[0-9a-fA-F:.]{2,45}""")
        private val dnsLabel = Regex("""[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?""")
        private val base64Url = Regex("""[A-Za-z0-9_-]{$KEY_LENGTH}""")
        private val shortIdHex = Regex("""([0-9a-fA-F]{2}){1,8}""")
        private val uuidText = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val deviceNameText = Regex("""[a-z0-9][a-z0-9._-]{0,31}""")

        fun looksLikeLink(text: String) = text.trimStart().startsWith("vpnchain://")

        fun parse(text: String): Result<PairingLink> = runCatching { read(text.trim()) }

        private fun read(text: String): PairingLink {
            if (!text.startsWith(PREFIX)) throw refuse(PairingException.Reason.NotALink, "That is not a relay code.")
            val fields = fieldsOf(text.removePrefix(PREFIX))
            val version = fields["v"]
            if (version != VERSION) {
                throw if (version?.toIntOrNull() != null) {
                    refuse(PairingException.Reason.UnsupportedVersion, "This code is from a newer version. Update the app.")
                } else {
                    invalid("v")
                }
            }
            val host = fields.field("h").let { if (it.startsWith('[')) it.removeSurrounding("[", "]").takeIf { ':' in it } else it }
            if (host == null || !validHost(host)) throw invalid("h")
            val port = fields.field("p").toIntOrNull()?.takeIf { it in 1..65535 } ?: throw invalid("p")
            val sni = fields.field("sni").takeIf(::validDnsName) ?: throw invalid("sni")
            return PairingLink(
                host = host,
                port = port,
                sni = sni,
                realityPublicKey = fields.field("pbk").takeIf(base64Url::matches) ?: throw invalid("pbk"),
                shortId = fields.field("sid").takeIf(shortIdHex::matches) ?: throw invalid("sid"),
                uuid = fields.field("id").takeIf(uuidText::matches) ?: throw invalid("id"),
                deviceName = fields.field("n").takeIf(deviceNameText::matches) ?: throw invalid("n"),
                token = fields.field("t").takeIf(base64Url::matches) ?: throw invalid("t"),
                expires = fields.field("e").toLongOrNull()?.takeIf { it > 0 } ?: throw invalid("e"),
            )
        }

        private fun Map<String, String>.field(name: String) = this[name] ?: throw invalid(name)

        private fun fieldsOf(query: String): Map<String, String> {
            val fields = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                val (name, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                if (fields.put(name, decode(value) ?: throw invalid(name)) != null) throw invalid(name)
            }
            return fields
        }

        private fun validHost(host: String): Boolean {
            val octets = ipv4.matchEntire(host)
            return when {
                octets != null -> octets.groupValues.drop(1).all { it.toInt() <= 255 }
                ':' in host -> ipv6.matches(host)
                else -> validDnsName(host)
            }
        }

        private fun validDnsName(name: String) =
            name.length <= 253 && name.split('.').all(dnsLabel::matches)

        // Names the field, never its value: the value may be the secret.
        private fun invalid(field: String) =
            refuse(PairingException.Reason.Invalid, "The relay code is damaged: $field is missing or wrong.")

        private fun refuse(reason: PairingException.Reason, message: String) = PairingException(reason, message)

        private fun encode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c.isLetterOrDigit() || c in "-._~")) {
                    append(c)
                } else {
                    append('%').append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[byte.toInt() and 0xF])
                }
            }
        }

        private fun decode(value: String): String? {
            val out = Buffer()
            var i = 0
            while (i < value.length) {
                if (value[i] == '%') {
                    val byte = value.substring(i + 1, minOf(i + 3, value.length)).takeIf { it.length == 2 }?.toIntOrNull(16)
                        ?: return null
                    out.writeByte(byte)
                    i += 3
                } else {
                    if (value[i].code > 0x7F) return null
                    out.writeByte(value[i].code)
                    i++
                }
            }
            return out.readUtf8()
        }
    }
}
