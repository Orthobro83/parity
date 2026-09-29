package app.parity.android.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import app.parity.core.scan.Box
import app.parity.core.scan.OcrLine
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
 * first use; the others are downloaded when a country needs them ([TessdataModels]). Every line
 * read carries Tesseract's confidence in it, 0–100.
 */
private const val TAG = "ParityOcr"

class TesseractReader(private val context: Context) {
    private val mutex = Mutex()
    private val downloads = Mutex()
    private var api: TessBaseAPI? = null
    private var loadedLanguages: String? = null
    /** Words confirmed on this phone. Loaded the next time an engine starts; the models themselves stay as shipped. */
    private var userWords: List<String> = emptyList()
    private val dataDir by lazy { File(context.filesDir, "tesseract") }

    /**
     * Remembers [words] and restarts the engine so the next read uses them. Tesseract can take a
     * word list; doing that before [TessBaseAPI.init] is the only point it reads one. Skipped when
     * the list has not changed, because restarting the engine is slow.
     */
    suspend fun noteUserWords(words: List<String>) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (words == userWords) return@withLock
            userWords = words
            writeUserWords(words)
            api?.recycle()
            api = null
            loadedLanguages = null
        }
    }

    /** Lines of [bitmap] read as one block of text: a small area such as a name. */
    suspend fun readBlock(bitmap: Bitmap, languages: String, textHeight: Float? = null): List<OcrLine>? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val tess = engineFor(languages) ?: return@withLock null
            recognizeLines(tess, bitmap, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, scaleFor(bitmap, textHeight))
        }
    }

    /**
     * Text lines with bounding boxes in [bitmap] coordinates. Automatic page segmentation reads
     * banners and prices best but can misread a tag's lines or find nothing at all on a busy
     * background (a tag photographed on a screen, glare); then the tag is read as one block of
     * lines, in sparse-text mode, and larger. The first attempt that reads the label's own script
     * confidently is taken; otherwise the one that read it with the most confidence, or failing any
     * script, the most letters. [textHeight] is the expected height of the tag's smaller text.
     */
    suspend fun readLines(bitmap: Bitmap, languages: String, textHeight: Float? = null): List<OcrLine>? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val tess = engineFor(languages) ?: return@withLock null
            val scale = scaleFor(bitmap, textHeight)
            val larger = minOf(scale * 2, 3f, MAX_SIDE / maxOf(bitmap.width, bitmap.height).toFloat())
            val attempts = buildList {
                add(TessBaseAPI.PageSegMode.PSM_AUTO to scale)
                // A straightened tag is one block of stacked lines; automatic layout can split them
                // oddly (on a rendered juice tag it misread the name where this read it right).
                add(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK to scale)
                add(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT to scale)
                if (larger >= scale * 1.4f) add(TessBaseAPI.PageSegMode.PSM_AUTO to larger)
            }
            var best: List<OcrLine> = emptyList()
            var bestConfidence = -1f
            for ((mode, attemptScale) in attempts) {
                val lines = recognizeLines(tess, bitmap, mode, attemptScale)
                // Lines with a word's worth of letters beyond Latin: the label's own script (a big
                // price can make automatic layout skip the name, "0 ر.س").
                val script = lines.filter { scriptLetters(it.text) >= 3 }
                val confidence = if (script.isEmpty()) -1f else script.map { it.confidence ?: 0f }.average().toFloat()
                if (confidence >= GOOD_CONFIDENCE) return@withLock lines
                if (confidence > bestConfidence || (confidence < 0f && bestConfidence < 0f && letters(lines) > letters(best))) {
                    best = lines
                    bestConfidence = confidence
                }
            }
            best
        }
    }

    /**
     * A price's own crop read as one line with only [characters] allowed (digits, separators,
     * currency signs), so a label's letters can't turn into digits there. Never used for names.
     */
    suspend fun readPrice(bitmap: Bitmap, languages: String, characters: String, textHeight: Float? = null): List<OcrLine>? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val tess = engineFor(languages) ?: return@withLock null
                tess.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, characters)
                try {
                    recognizeLines(tess, bitmap, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE, scaleFor(bitmap, textHeight))
                } finally {
                    tess.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "")
                }
            }
        }

    /** Letters outside Latin, where a label's own script is. */
    private fun scriptLetters(text: String) = text.count { it.isLetter() && it.code > 0x24F }

    private fun letters(lines: List<OcrLine>) = lines.sumOf { line -> line.text.count { it.isLetter() } }

    private fun recognizeLines(tess: TessBaseAPI, bitmap: Bitmap, mode: Int, scale: Float): List<OcrLine> {
        val prepared = prepare(bitmap, scale)
        val started = SystemClock.elapsedRealtime()
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            runCatching { File(context.cacheDir, "ocr_last.png").outputStream().use { prepared.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        try {
            tess.setPageSegMode(mode)
            tess.setImage(prepared)
            tess.getUTF8Text() // runs recognition
            val iterator = tess.getResultIterator() ?: return emptyList()
            val lines = mutableListOf<OcrLine>()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                    val r = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (!text.isNullOrEmpty() && r != null) {
                        val box = Box(r.left / scale, r.top / scale, r.right / scale, r.bottom / scale)
                        lines += OcrLine(text, box, confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } finally {
                iterator.delete()
            }
            Log.d(TAG, "lines psm=$mode ${prepared.width}x${prepared.height} in ${SystemClock.elapsedRealtime() - started} ms: ${lines.joinToString(" | ") { "${it.text} (${it.confidence?.toInt()})" }}")
            return lines
        } finally {
            tess.clear()
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
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
            val sha = try {
                connection.inputStream.use { input -> copyHashed(input, partial) }
            } finally {
                connection.disconnect()
            }
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

    /** Copies [input] to [target] and returns the SHA-256 of what was copied. */
    private fun copyHashed(input: java.io.InputStream, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                output.write(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun engineFor(languages: String): TessBaseAPI? {
        if (loadedLanguages == languages) return api
        // Every model is needed: English alone would read another script as Latin gibberish.
        if (!languages.split('+').all { ensureModel(it) }) return null
        api?.recycle()
        api = null
        loadedLanguages = null
        val started = SystemClock.elapsedRealtime()
        val engine = TessBaseAPI()
        if (userWords.isNotEmpty()) {
            writeUserWords(userWords, languages.split('+'))
            // Init-only: the suffix names tessdata/<lang>.user-words, and it is read during init.
            engine.setVariable("user_words_suffix", "user-words")
        }
        if (!engine.init(dataDir.absolutePath, languages)) {
            Log.w(TAG, "Tesseract couldn't load $languages")
            engine.recycle()
            return null
        }
        Log.d(TAG, "loaded $languages in ${SystemClock.elapsedRealtime() - started} ms (Tesseract ${engine.version})")
        engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        api = engine
        loadedLanguages = languages
        return engine
    }

    /** One word per line, for English and every language this engine loads. */
    private fun writeUserWords(words: List<String>, languages: List<String> = emptyList()) {
        val dir = File(dataDir, "tessdata").apply { mkdirs() }
        val body = words.joinToString("\n") { it.replace('\n', ' ').replace('\t', ' ').trim() }
        (languages + "eng").map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { code ->
            File(dir, "$code.user-words").writeText(body)
        }
    }

    /**
     * True when [language]'s model is on the phone. One the app ships is copied from it, checked
     * against its pinned digest, on first use and whenever an update ships another version.
     */
    private fun ensureModel(language: String): Boolean {
        val target = File(dataDir, "tessdata/$language.traineddata")
        val shipped = TessdataModels.bundled[language]
        if (target.length() > 0 && (shipped == null || target.length() == shipped.bytes)) return true
        shipped ?: return false
        val partial = File(target.path + ".part")
        return runCatching {
            target.parentFile?.mkdirs()
            val sha = context.assets.open("tessdata/$language.traineddata").use { copyHashed(it, partial) }
            check(partial.length() == shipped.bytes && sha == shipped.sha256) { "shipped $language model doesn't match its checksum" }
            target.delete()
            check(partial.renameTo(target)) { "couldn't store the $language model" }
            true
        }.onFailure {
            Log.w(TAG, "model $language not installed", it)
            partial.delete()
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

        /**
         * Mean confidence in the script's lines at which an attempt is taken without trying the
         * others: above the name threshold ([app.parity.core.scan.NameText.MIN_CONFIDENCE]).
         */
        const val GOOD_CONFIDENCE = 80f
    }
}
