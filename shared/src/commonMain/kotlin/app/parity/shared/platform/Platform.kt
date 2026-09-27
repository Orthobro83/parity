package app.parity.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.parity.core.scan.Box
import app.parity.core.scan.OcrFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the shared app needs from the phone. Android implements it in androidApp; iOS will in
 * Phase 2 (design §2).
 */
interface Platform {
    val camera: CameraScanner
    val text: TextServices
    val location: LocationService
    val permissions: Permissions
    val files: FileService
    val qr: QrEncoder
    val haptics: Haptics
}

enum class ScanMode {
    /** Price tags: text recognition plus barcodes, and Parity QR frames switch to receive mode. */
    PRICE_TAGS,

    /** Receiving a QR transfer: barcode scanning only, as fast as possible. */
    QR_ONLY,
}

interface CameraScanner {
    /** Live preview that analyses frames while [active]. Callbacks arrive on a background thread. */
    @Composable
    fun Preview(
        modifier: Modifier,
        mode: ScanMode,
        active: Boolean,
        onFrame: (OcrFrame) -> Unit,
        onQrCode: (String) -> Unit,
    )

    /**
     * Re-reads [region] of the most recent frame with a script-specific OCR engine (Tesseract),
     * for names in scripts the fast recognizer can't read, such as Georgian. [languages] are
     * Tesseract codes, e.g. "kat+eng".
     */
    suspend fun readRegion(region: Box, languages: String): String?

    /** Turns the torch on or off, for dim store aisles. */
    fun setTorch(on: Boolean)
}

interface TextServices {
    /** BCP-47 language of [text], or null when unsure. */
    suspend fun identifyLanguage(text: String): String?

    /** Translates on-device; downloads the language model if needed and allowed. Null on failure. */
    suspend fun translate(text: String, from: String, to: String): String?

    /** Downloads the translation model ahead of time (on Wi-Fi), e.g. when arriving in a new country. */
    suspend fun prepare(from: String, to: String)
}

data class LocationFix(val countryCode: String?, val lat: Double?, val lng: Double?)

interface LocationService {
    /** A coarse fix plus the country, or the mobile network's country when location is off. */
    suspend fun current(): LocationFix?
}

interface Permissions {
    val camera: StateFlow<Boolean>
    val location: StateFlow<Boolean>
    fun requestCamera()
    fun requestLocation()
}

interface FileService {
    /** Lets the user choose where to save [bytes]. Returns true when saved. */
    suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean

    /** Lets the user pick a file to open. Returns its bytes, or null when cancelled. */
    suspend fun open(mimeTypes: List<String>): ByteArray?
}

/** A QR code as a square grid of dark (true) and light modules. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

interface QrEncoder {
    /** Encodes [text] (Base45, so QR alphanumeric mode) at error correction level M. */
    fun encode(text: String): QrMatrix
}

interface Haptics {
    fun tick()
    fun success()
}
