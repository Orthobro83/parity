package app.parity.core.transfer

/**
 * QR transfer framing (design §8.5).
 *
 * Every code carries its position: "1 of 1" means this code is the whole transfer; "2 of 4" means
 * keep scanning until codes 1–4 have all been seen for this transfer ID.
 *
 * Frame bytes (then Base45-encoded into a QR alphanumeric string):
 * ```
 * 0-1   magic "PY"
 * 2     version (1)
 * 3     flags: bit0 encrypted, bit1 deflated
 * 4-11  transfer ID (random per transfer)
 * 12-13 frame index, 1-based
 * 14-15 total frames
 * 16-23 first 8 bytes of SHA-256 of the complete (compressed) payload
 * 24..  chunk bytes
 * last4 CRC-32 of everything before it
 * ```
 */
object QrTransfer {
    const val VERSION: Byte = 1
    const val FLAG_ENCRYPTED = 1
    const val FLAG_DEFLATED = 2

    /** Payload bytes per frame. Keeps each code around QR version 25 at error correction M. */
    const val CHUNK_SIZE = 700
    private const val HEADER = 24
    private const val TRAILER = 4

    data class Frame(
        val transferId: ByteArray,
        val index: Int,
        val total: Int,
        val flags: Int,
        val payloadHash: ByteArray,
        val chunk: ByteArray,
    ) {
        val transferKey: String get() = transferId.toHex()
    }

    /** Splits [payload] into QR strings. Compresses when that makes it smaller. */
    fun encode(payload: ByteArray, encrypted: Boolean = false, transferId: ByteArray = secureRandomBytes(8)): List<String> {
        val compressed = deflate(payload)
        val useDeflate = compressed.size < payload.size
        val body = if (useDeflate) compressed else payload
        val flags = (if (encrypted) FLAG_ENCRYPTED else 0) or (if (useDeflate) FLAG_DEFLATED else 0)
        val hash = Sha256.digest(body).copyOf(8)
        val chunks = if (body.isEmpty()) listOf(ByteArray(0)) else body.toList().chunked(CHUNK_SIZE).map { it.toByteArray() }
        require(chunks.size <= 0xFFFF) { "Payload too large for QR transfer" }
        return chunks.mapIndexed { i, chunk ->
            Base45.encode(frameBytes(Frame(transferId, i + 1, chunks.size, flags, hash, chunk)))
        }
    }

    fun frameBytes(frame: Frame): ByteArray {
        val out = ByteArray(HEADER + frame.chunk.size + TRAILER)
        out[0] = 'P'.code.toByte(); out[1] = 'Y'.code.toByte(); out[2] = VERSION; out[3] = frame.flags.toByte()
        frame.transferId.copyInto(out, 4, 0, 8)
        out[12] = (frame.index shr 8).toByte(); out[13] = frame.index.toByte()
        out[14] = (frame.total shr 8).toByte(); out[15] = frame.total.toByte()
        frame.payloadHash.copyInto(out, 16, 0, 8)
        frame.chunk.copyInto(out, HEADER)
        val crc = Crc32.of(out, 0, out.size - TRAILER)
        for (i in 0 until 4) out[out.size - 4 + i] = (crc ushr (24 - 8 * i)).toByte()
        return out
    }

    /** Parses one scanned QR string. Returns null for anything that is not a valid Parity frame. */
    fun decodeFrame(text: String): Frame? {
        val bytes = Base45.decode(text.trim()) ?: return null
        if (bytes.size < HEADER + TRAILER) return null
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'Y'.code.toByte() || bytes[2] != VERSION) return null
        val expectedCrc = Crc32.of(bytes, 0, bytes.size - TRAILER)
        var actualCrc = 0
        for (i in 0 until 4) actualCrc = (actualCrc shl 8) or (bytes[bytes.size - 4 + i].toInt() and 0xFF)
        if (expectedCrc != actualCrc) return null
        val index = (bytes[12].toInt() and 0xFF shl 8) or (bytes[13].toInt() and 0xFF)
        val total = (bytes[14].toInt() and 0xFF shl 8) or (bytes[15].toInt() and 0xFF)
        if (total < 1 || index !in 1..total) return null
        return Frame(
            transferId = bytes.copyOfRange(4, 12),
            index = index,
            total = total,
            flags = bytes[3].toInt() and 0xFF,
            payloadHash = bytes.copyOfRange(16, 24),
            chunk = bytes.copyOfRange(HEADER, bytes.size - TRAILER),
        )
    }

    /** Quick check used by the Home camera to switch into receive mode. */
    fun looksLikeFrame(text: String): Boolean = decodeFrame(text) != null
}

/** Collects frames on the receiving phone until it has "every number from 1 to N" (design §8.5). */
class QrReassembler {
    sealed interface Event {
        /** Not a Parity code, a damaged read, or a repeat of a code already received. */
        data object Ignored : Event

        data class Progress(val received: Set<Int>, val total: Int) : Event

        /** A code from a different transfer arrived part-way through; the UI asks whether to start over. */
        data class DifferentTransfer(val frame: QrTransfer.Frame) : Event

        data class Complete(val payload: ByteArray, val encrypted: Boolean) : Event

        /** All frames arrived but the payload did not match its hash; the receiver starts over. */
        data object Corrupt : Event
    }

    private var transferKey: String? = null
    private var total = 0
    private var hash = ByteArray(0)
    private var flags = 0
    private val chunks = mutableMapOf<Int, ByteArray>()

    val receivedCount: Int get() = chunks.size
    val totalCount: Int get() = total

    fun accept(text: String): Event {
        val frame = QrTransfer.decodeFrame(text) ?: return Event.Ignored
        return accept(frame)
    }

    fun accept(frame: QrTransfer.Frame): Event {
        val key = transferKey
        if (key == null) {
            start(frame)
        } else if (key != frame.transferKey) {
            return Event.DifferentTransfer(frame)
        }
        if (frame.index in chunks) return Event.Ignored
        chunks[frame.index] = frame.chunk
        if (chunks.size < total) return Event.Progress(chunks.keys.toSet(), total)

        val body = (1..total).fold(ByteArray(0)) { acc, i -> acc + chunks.getValue(i) }
        if (!Sha256.digest(body).copyOf(8).contentEquals(hash)) {
            reset()
            return Event.Corrupt
        }
        val payload = if (flags and QrTransfer.FLAG_DEFLATED != 0) inflate(body) else body
        val encrypted = flags and QrTransfer.FLAG_ENCRYPTED != 0
        reset()
        return Event.Complete(payload, encrypted)
    }

    /** Starts over with [frame]'s transfer (after the user confirmed "start over"). */
    fun restartWith(frame: QrTransfer.Frame): Event {
        reset()
        return accept(frame)
    }

    fun reset() {
        transferKey = null
        total = 0
        hash = ByteArray(0)
        flags = 0
        chunks.clear()
    }

    private fun start(frame: QrTransfer.Frame) {
        transferKey = frame.transferKey
        total = frame.total
        hash = frame.payloadHash
        flags = frame.flags
    }
}
