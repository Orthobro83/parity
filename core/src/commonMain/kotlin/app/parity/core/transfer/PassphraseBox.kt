package app.parity.core.transfer

/** PBKDF2-HMAC-SHA256. Platform crypto on each target. */
expect fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray

/** AES-256-GCM with a 128-bit tag appended to the ciphertext. */
expect fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray

/** Returns null when the key is wrong or the data was altered. */
expect fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray?

/**
 * Encrypts QR backups with a code the sender shows and the receiver types (design §8.5). The code
 * is never inside the QR codes, so someone who films the screen still can't read the data.
 *
 * Sealed format: version (1) | salt (16) | IV (12) | AES-GCM ciphertext + tag.
 */
object PassphraseBox {
    /** Crockford Base32: no I, L, O or U, so the code is easy to read aloud and type. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val VERSION: Byte = 1
    const val ITERATIONS = 310_000
    const val CODE_LENGTH = 12 // 60 bits

    /** A random code formatted for display, e.g. "K7QM-2XHP-9RTW". */
    fun newCode(): String {
        val bytes = secureRandomBytes(CODE_LENGTH)
        val raw = bytes.map { ALPHABET[(it.toInt() and 0xFF) % 32] }.joinToString("")
        return raw.chunked(4).joinToString("-")
    }

    /** Accepts lower case, spaces and dashes, and the usual look-alikes (O→0, I/L→1). */
    fun normalize(code: String): String = code.uppercase()
        .filter { it.isLetterOrDigit() }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
        .joinToString("")

    fun isValidCode(code: String): Boolean {
        val n = normalize(code)
        return n.length == CODE_LENGTH && n.all { it in ALPHABET }
    }

    fun seal(plaintext: ByteArray, code: String): ByteArray {
        val salt = secureRandomBytes(16)
        val iv = secureRandomBytes(12)
        val key = pbkdf2Sha256(normalize(code).toCharArray(), salt, ITERATIONS, 32)
        return byteArrayOf(VERSION) + salt + iv + aesGcmEncrypt(key, iv, plaintext)
    }

    fun open(sealed: ByteArray, code: String): ByteArray? {
        if (sealed.size < 1 + 16 + 12 + 16 || sealed[0] != VERSION) return null
        val salt = sealed.copyOfRange(1, 17)
        val iv = sealed.copyOfRange(17, 29)
        val key = pbkdf2Sha256(normalize(code).toCharArray(), salt, ITERATIONS, 32)
        return aesGcmDecrypt(key, iv, sealed.copyOfRange(29, sealed.size))
    }
}
