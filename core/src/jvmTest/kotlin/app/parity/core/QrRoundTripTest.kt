package app.parity.core

import app.parity.core.transfer.PassphraseBox
import app.parity.core.transfer.QrReassembler
import app.parity.core.transfer.QrTransfer
import app.parity.core.transfer.deflate
import app.parity.core.transfer.inflate
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Encodes frames as real QR images and reads them back with an independent decoder. */
class QrRoundTripTest {
    private class MatrixSource(private val m: BitMatrix) : LuminanceSource(m.width, m.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (m[x, y]) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray = ByteArray(width * height) { i -> if (m[i % width, i / width]) 0 else 255.toByte() }
    }

    private fun throughQr(text: String): String {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val bitmap = BinaryBitmap(HybridBinarizer(MatrixSource(matrix)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test
    fun framesSurviveRealQrCodes() {
        val payload = Random(3).nextBytes(3_000)
        val codes = QrTransfer.encode(payload)
        assertEquals(5, codes.size)
        val reassembler = QrReassembler()
        var last: QrReassembler.Event? = null
        codes.forEach { code ->
            val read = throughQr(code)
            assertEquals(code, read)
            last = reassembler.accept(read)
        }
        assertContentEquals(payload, assertIs<QrReassembler.Event.Complete>(last).payload)
    }

    @Test
    fun encryptedBackupSurvivesQrAndOpensWithTheCode() {
        val backup = ("stores.csv,products.csv,price_observations.csv\n" + "row,".repeat(2_000)).encodeToByteArray()
        val code = PassphraseBox.newCode()
        val codes = QrTransfer.encode(PassphraseBox.seal(deflate(backup), code), encrypted = true)
        val reassembler = QrReassembler()
        var last: QrReassembler.Event? = null
        codes.reversed().forEach { last = reassembler.accept(throughQr(it)) }
        val complete = assertIs<QrReassembler.Event.Complete>(last)
        kotlin.test.assertTrue(complete.encrypted)
        assertContentEquals(backup, inflate(PassphraseBox.open(complete.payload, code)!!))
        kotlin.test.assertNull(PassphraseBox.open(complete.payload, "AAAA-AAAA-AAAA"))
    }

    @Test
    fun fullSizeFrameFitsAScannableCode() {
        val code = QrTransfer.encode(Random(9).nextBytes(QrTransfer.CHUNK_SIZE)).single()
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        val version = (matrix.width - 17) / 4
        println("700-byte frame → ${code.length} chars → QR version $version (${matrix.width}×${matrix.width})")
        kotlin.test.assertTrue(version <= 27, "frame needs QR version $version")
    }
}
