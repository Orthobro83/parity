package app.parity.core.transfer

/**
 * RFC 9285 Base45. Its alphabet is exactly the QR alphanumeric character set, so binary frames
 * are carried in the dense alphanumeric mode and survive every scanner's text decoding.
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private val index = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(data.size * 3 / 2 + 2)
        var i = 0
        while (i + 1 < data.size) {
            val n = (data[i].toInt() and 0xFF) * 256 + (data[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[n % 45]).append(ALPHABET[(n / 45) % 45]).append(ALPHABET[n / 2025])
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
        }
        return sb.toString()
    }

    /** Returns null for text that is not valid Base45. */
    fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null
        val out = ArrayList<Byte>(text.length * 2 / 3)
        var i = 0
        while (i < text.length) {
            val remaining = text.length - i
            val c = value(text[i]) ?: return null
            val d = value(text[i + 1]) ?: return null
            if (remaining >= 3) {
                val e = value(text[i + 2]) ?: return null
                val n = c + d * 45 + e * 2025
                if (n > 0xFFFF) return null
                out += (n shr 8).toByte(); out += (n and 0xFF).toByte()
                i += 3
            } else {
                val n = c + d * 45
                if (n > 0xFF) return null
                out += n.toByte()
                i += 2
            }
        }
        return out.toByteArray()
    }

    private fun value(ch: Char): Int? = if (ch.code < 128) index[ch.code].takeIf { it >= 0 } else null
}
