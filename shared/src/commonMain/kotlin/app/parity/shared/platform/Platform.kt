package app.parity.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.parity.core.scan.Box
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.TagQuad
import app.parity.core.scan.TextScript
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
    /** The fast recognizer for the labels here: Latin, or Chinese, Japanese, Korean or Devanagari. */
    var script: TextScript

    /**
     * Makes sure the Tesseract models for [languages] ("hye+eng") are on the phone, downloading any
     * that aren't (1–10 MB each). True when they're ready.
     */
    suspend fun prepareReader(languages: String): Boolean

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
     * Re-reads [region] of frame [frameId] with a script-specific OCR engine (Tesseract), for names
     * in scripts the fast recognizer can't read, such as Georgian. [languages] are Tesseract codes,
     * e.g. "kat+eng". [textHeight] is the expected height of the letters in frame pixels, if known.
     * Only stills and photos are read (design §6.2): null for a live frame, or one no longer kept.
     */
    suspend fun readRegion(frameId: Long, region: Box, languages: String, textHeight: Float? = null): String?

    /**
     * Reads the text lines, with their boxes in frame coordinates, in [region] of frame [frameId]
     * using the script-specific engine. Used for a second pass over tags in scripts like Georgian.
     * With [quad], that tag is read instead, straightened (design §6.1).
     */
    suspend fun readLines(frameId: Long, region: Box, languages: String, textHeight: Float? = null, quad: TagQuad? = null): List<OcrLine>?

    /** Reads a photo (JPEG/PNG bytes) like a camera frame; it then backs [readRegion]. */
    suspend fun scanImage(bytes: ByteArray): OcrFrame?

    /**
     * The shutter: takes a full-resolution photo of what the preview shows and reads it like
     * [scanImage]. Null when the camera isn't running or the photo fails. Where the camera can't
     * take stills it reads the latest preview frame instead, unless [stillOnly].
     */
    suspend fun capture(stillOnly: Boolean = false): OcrFrame?
}

interface TextServices {
    /** BCP-47 language of [text], or null when unsure. */
    suspend fun identifyLanguage(text: String): String?

    /**
     * Translates on-device with language packs already on the phone; never downloads one. Null when
     * a pack is missing or the translation fails.
     */
    suspend fun translate(text: String, from: String, to: String): String?

    /** True when the language packs for [from] → [to] are on the device. */
    suspend fun isTranslationReady(from: String, to: String): Boolean

    /**
     * Downloads the language packs for offline translation (about 30 MB each); on Wi-Fi only unless
     * [wifiOnly] is false. True once they're ready.
     */
    suspend fun prepare(from: String, to: String, wifiOnly: Boolean = true): Boolean

    /** Languages with an offline pack on the phone. */
    suspend fun offlineLanguages(): Set<String>

    /** Removes [language]'s offline pack. */
    suspend fun deleteOffline(language: String): Boolean
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
