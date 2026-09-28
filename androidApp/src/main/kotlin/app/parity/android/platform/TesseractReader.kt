package app.parity.android.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Tesseract for labels in scripts ML Kit can't read, such as Georgian or Armenian (design §14).
 * Georgian, Russian and English models ship in assets/tessdata and are copied to app storage on
 * first use; the others are downloaded when a country needs them ([TessdataModels]).
 */
private const val TAG = "ParityOcr"

class TesseractReader(private val context: Context) {
    private val mutex = Mutex()
    private val downloads = Mutex()
    private var api: TessBaseAPI? = null
    private var loadedLanguages: String? = null
    private val dataDir by lazy { File(context.filesDir, "tesseract") }

    /** Reads [bitmap] as one block of text. [textHeight] is the expected height of its letters, if known. */
    suspend fun read(bitmap: Bitmap, languages: String, textHeight: Float? = null): String? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val started = SystemClock.elapsedRealtime()
            val tess = engineFor(languages) ?: return@withLock null
            val ready = SystemClock.elapsedRealtime()
            val prepared = prepare(bitmap, scaleFor(bitmap, textHeight))
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                runCatching { File(context.cacheDir, "ocr_last.png").outputStream().use { prepared.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
            try {
                tess.setImage(prepared)
                tess.getUTF8Text()?.trim()?.takeIf { it.isNotEmpty() }.also {
                    Log.d(TAG, "read ${prepared.width}x${prepared.height} in ${SystemClock.elapsedRealtime() - ready} ms (init ${ready - started} ms): $it")
                }
            } finally {
                tess.clear()
                if (prepared !== bitmap) prepared.recycle()
            }
        }
    }

    /**
     * Text lines with bounding boxes in [bitmap] coordinates. Automatic page segmentation reads
     * banners and prices best but can find nothing at all on a busy background (a tag photographed
     * on a screen, glare); then sparse-text mode and a larger upscale are tried until words turn up.
     * [textHeight] is the expected height of the tag's smaller text, if known.
     */
    suspend fun readLines(bitmap: Bitmap, languages: String, textHeight: Float? = null): List<Pair<String, Rect>>? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val tess = engineFor(languages) ?: return@withLock null
            val scale = scaleFor(bitmap, textHeight)
            val larger = minOf(scale * 2, 3f, MAX_SIDE / maxOf(bitmap.width, bitmap.height).toFloat())
            val attempts = buildList {
                add(TessBaseAPI.PageSegMode.PSM_AUTO to scale)
                add(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT to scale)
                if (larger >= scale * 1.4f) add(TessBaseAPI.PageSegMode.PSM_AUTO to larger)
            }
            try {
                // Stop once a line has a word's worth of letters beyond Latin (the label's own script:
                // a big price can make automatic layout skip the name, "0 ر.س"); otherwise keep the
                // attempt that found the most.
                var best: List<Pair<String, Rect>> = emptyList()
                for ((mode, attemptScale) in attempts) {
                    val lines = recognizeLines(tess, bitmap, mode, attemptScale)
                    if (lines.any { (text, _) -> scriptLetters(text) >= 3 }) return@withLock lines
                    if (letters(lines) > letters(best)) best = lines
                }
                best
            } finally {
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            }
        }
    }

    /** Letters outside Latin, where a label's own script is. */
    private fun scriptLetters(text: String) = text.count { it.isLetter() && it.code > 0x24F }

    private fun letters(lines: List<Pair<String, Rect>>) = lines.sumOf { (text, _) -> text.count { it.isLetter() } }

    private fun recognizeLines(tess: TessBaseAPI, bitmap: Bitmap, mode: Int, scale: Float): List<Pair<String, Rect>> {
        val prepared = prepare(bitmap, scale)
        val started = SystemClock.elapsedRealtime()
        try {
            tess.setPageSegMode(mode)
            tess.setImage(prepared)
            tess.getUTF8Text() // runs recognition
            val iterator = tess.getResultIterator() ?: return emptyList()
            val lines = mutableListOf<Pair<String, Rect>>()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                    val r = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (!text.isNullOrEmpty() && r != null) {
                        lines += text to Rect((r.left / scale).toInt(), (r.top / scale).toInt(), (r.right / scale).toInt(), (r.bottom / scale).toInt())
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } finally {
                iterator.delete()
            }
            Log.d(TAG, "lines psm=$mode ${prepared.width}x${prepared.height} in ${SystemClock.elapsedRealtime() - started} ms: ${lines.joinToString(" | ") { it.first }}")
            return lines
        } finally {
            tess.clear()
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    /**
     * Makes sure every model in [languages] ("hye+eng") is on the phone, downloading the missing ones
     * from the pinned tessdata_fast release. True when all are ready.
     */
    suspend fun prepare(languages: String): Boolean = withContext(Dispatchers.IO) {
        downloads.withLock { languages.split('+').all { ensureModel(it) || download(it) } }
    }

    private fun download(language: String): Boolean {
        val model = TessdataModels.downloadable[language] ?: return false
        val target = File(dataDir, "tessdata/$language.traineddata")
        val partial = File(target.path + ".part")
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            target.parentFile?.mkdirs()
            val connection = URL(TessdataModels.BASE_URL + "$language.traineddata").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            // Only a file with exactly the pinned contents is used.
            check(partial.length() == model.bytes && sha == model.sha256) { "$language model doesn't match its checksum" }
            check(partial.renameTo(target)) { "couldn't store the $language model" }
            Log.d(TAG, "downloaded $language (${model.bytes / 1024} KB) in ${SystemClock.elapsedRealtime() - started} ms")
            true
        }.onFailure {
            Log.w(TAG, "model $language not downloaded", it)
            partial.delete()
        }.getOrDefault(false)
    }

    private fun engineFor(languages: String): TessBaseAPI? {
        if (loadedLanguages == languages) return api
        // Every model is needed: English alone would read another script as Latin gibberish.
        if (!languages.split('+').all { ensureModel(it) }) return null
        api?.recycle()
        api = null
        loadedLanguages = null
        val engine = TessBaseAPI()
        if (!engine.init(dataDir.absolutePath, languages)) {
            engine.recycle()
            return null
        }
        engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        api = engine
        loadedLanguages = languages
        return engine
    }

    /** True when [language]'s model is on the phone, copying it from the app if it ships there. */
    private fun ensureModel(language: String): Boolean {
        val target = File(dataDir, "tessdata/$language.traineddata")
        if (target.exists() && target.length() > 0) return true
        if (language in TessdataModels.downloadable) return false
        return runCatching {
            target.parentFile?.mkdirs()
            context.assets.open("tessdata/$language.traineddata").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Scale so letters are about 36 px tall, which Tesseract reads best: live camera frames show a
     * tag's name only 15–25 px tall, while a full-resolution still can show it 80 px tall, which
     * only makes Tesseract slow. Without a [textHeight], small crops are upscaled by size.
     */
    private fun scaleFor(source: Bitmap, textHeight: Float?): Float {
        val byText = textHeight?.takeIf { it > 0f }?.let { (36f / it).coerceIn(0.5f, 3f) }
        val scale = byText ?: when {
            source.height < 60 -> 3f
            source.height < 120 -> 2f
            else -> 1f
        }
        // Never upscale past MAX_SIDE.
        return if (scale <= 1f) scale else minOf(scale, maxOf(1f, MAX_SIDE / maxOf(source.width, source.height).toFloat()))
    }

    /**
     * Grayscale, upscale, and stretch the contrast so the darkest ink is black and the tag white:
     * phone frames of a tag are often grey on grey.
     */
    private fun prepare(source: Bitmap, scale: Float): Bitmap {
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).apply {
            scale(scale, scale)
            drawBitmap(source, 0f, 0f, paint)
        }
        stretchContrast(out)
        return out
    }

    private fun stretchContrast(bitmap: Bitmap) {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = IntArray(256)
        for (p in px) histogram[p and 0xFF]++
        val low = percentile(histogram, px.size, 0.01)
        val high = percentile(histogram, px.size, 0.99)
        if (high - low < 24) return // blank, or already full contrast is impossible to tell
        val range = (high - low).toFloat()
        for (i in px.indices) {
            val g = (((px[i] and 0xFF) - low) * 255 / range).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        bitmap.setPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        var seen = 0
        for (level in 0..255) {
            seen += histogram[level]
            if (seen >= total * fraction) return level
        }
        return 255
    }

    private companion object {
        /** Longest side of an image handed to Tesseract, to bound its time on a phone. */
        const val MAX_SIDE = 2400f
    }
}
