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

/**
 * Tesseract for product names in scripts ML Kit can't read, such as Georgian (design §14).
 * Model files ship in assets/tessdata and are copied to app storage on first use.
 */
private const val TAG = "ParityOcr"

class TesseractReader(private val context: Context) {
    private val mutex = Mutex()
    private var api: TessBaseAPI? = null
    private var loadedLanguages: String? = null
    private val dataDir by lazy { File(context.filesDir, "tesseract") }

    suspend fun read(bitmap: Bitmap, languages: String): String? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val started = SystemClock.elapsedRealtime()
            val tess = engineFor(languages) ?: return@withLock null
            val ready = SystemClock.elapsedRealtime()
            val prepared = prepare(bitmap)
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

    /** Text lines with bounding boxes in [bitmap] coordinates, using automatic page segmentation. */
    suspend fun readLines(bitmap: Bitmap, languages: String): List<Pair<String, Rect>>? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val tess = engineFor(languages) ?: return@withLock null
            val scale = scaleFor(bitmap)
            val prepared = prepare(bitmap)
            val started = SystemClock.elapsedRealtime()
            try {
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                tess.setImage(prepared)
                tess.getUTF8Text() // runs recognition
                val iterator = tess.getResultIterator() ?: return@withLock null
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
                Log.d(TAG, "lines ${prepared.width}x${prepared.height} in ${SystemClock.elapsedRealtime() - started} ms: ${lines.joinToString(" | ") { it.first }}")
                lines
            } finally {
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                tess.clear()
                if (prepared !== bitmap) prepared.recycle()
            }
        }
    }

    private fun engineFor(languages: String): TessBaseAPI? {
        if (loadedLanguages == languages) return api
        api?.recycle()
        api = null
        loadedLanguages = null
        val available = languages.split('+').filter { ensureModel(it) }
        if (available.isEmpty()) return null
        val engine = TessBaseAPI()
        val joined = available.joinToString("+")
        if (!engine.init(dataDir.absolutePath, joined)) {
            engine.recycle()
            return null
        }
        engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        api = engine
        loadedLanguages = languages
        return engine
    }

    private fun ensureModel(language: String): Boolean {
        val target = File(dataDir, "tessdata/$language.traineddata")
        if (target.exists() && target.length() > 0) return true
        return runCatching {
            target.parentFile?.mkdirs()
            context.assets.open("tessdata/$language.traineddata").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            true
        }.getOrDefault(false)
    }

    private fun scaleFor(source: Bitmap): Float = when {
        source.height < 60 -> 3f
        source.height < 120 -> 2f
        else -> 1f
    }

    /** Grayscale and upscale small crops so glyphs are ~40 px tall, which Tesseract prefers. */
    private fun prepare(source: Bitmap): Bitmap {
        val scale = scaleFor(source)
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
        return out
    }
}
