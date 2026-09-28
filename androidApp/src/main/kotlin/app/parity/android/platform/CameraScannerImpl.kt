package app.parity.android.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import app.parity.core.scan.Box
import app.parity.core.scan.OcrElement
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.TagQuad
import app.parity.shared.platform.CameraScanner
import app.parity.shared.platform.ScanMode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import app.parity.core.scan.TextScript
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.camera.core.Preview as CameraPreview

/**
 * CameraX preview plus ML Kit analysis (design §6): Latin text and barcodes for price tags,
 * QR codes for transfers. Frames are cropped to what the preview shows, and recent ones are kept
 * so a region can be re-read by Tesseract.
 */
class CameraScannerImpl(
    private val context: Context,
    private val bridge: ActivityBridge,
    private val tesseract: TesseractReader,
) : CameraScanner {
    @Volatile override var script: TextScript = TextScript.LATIN
    private val recognizers = mutableMapOf<TextScript, TextRecognizer>()

    /** The recognizer for [script]; each also reads Latin letters and digits. */
    private val textRecognizer: TextRecognizer
        get() = synchronized(recognizers) {
            recognizers.getOrPut(script) {
                TextRecognition.getClient(
                    when (script) {
                        TextScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                        TextScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                        TextScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                        TextScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                        TextScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                    },
                )
            }
        }

    override suspend fun prepareReader(languages: String): Boolean = tesseract.prepare(languages)
    private val tagBarcodes by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            ).build(),
        )
    }
    private val qrOnly by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * The latest camera frame, for the shutter where stills can't be taken, and recent stills and
     * photos by frame id, which Tesseract re-reads. Live frames show small print too coarsely for
     * it (design §6.2), so they aren't kept for that.
     */
    private val frames = object : LinkedHashMap<Long, Pair<Bitmap, Int>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<Bitmap, Int>>?) = size > 1
    }
    private val photos = object : LinkedHashMap<Long, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > 2
    }
    private val nextFrameId = AtomicLong(1)
    @Volatile private var imageCapture: ImageCapture? = null

    /** A still is being taken and read: live frames wait, so they don't hold up its delivery and OCR. */
    @Volatile private var stillBusy = false

    @Composable
    override fun Preview(
        modifier: Modifier,
        mode: ScanMode,
        active: Boolean,
        onFrame: (OcrFrame) -> Unit,
        onQrCode: (String) -> Unit,
    ) {
        // Debug builds only: a still picture in place of the camera, for screenshots that look like
        // a store rather than the emulator's virtual room (PROGRESS.md, "Screenshots").
        val backdrop = remember(mode) { if (mode == ScanMode.PRICE_TAGS) demoBackdrop() else null }
        if (backdrop != null) {
            Image(backdrop, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
            return
        }
        val previewView = remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
        val activeState = rememberUpdatedState(active)
        val frameCallback = rememberUpdatedState(onFrame)
        val qrCallback = rememberUpdatedState(onQrCode)
        val scope = rememberCoroutineScope()

        AndroidView(factory = { previewView }, modifier = modifier)

        DisposableEffect(mode) {
            var provider: ProcessCameraProvider? = null
            var useCases: Array<UseCase> = emptyArray()
            val job = scope.launch {
                val owner = bridge.activityOrNull ?: return@launch
                val p = cameraProvider()
                provider = p
                val preview = CameraPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, Analyzer(mode, activeState, frameCallback, qrCallback))
                // Stills for the shutter, sharper than analysis frames for small print.
                val still = if (mode == ScanMode.PRICE_TAGS) {
                    ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                                .build(),
                        )
                        .build()
                } else null
                useCases = listOfNotNull(preview, analysis, still).toTypedArray()
                // With the preview's viewport, each frame's crop rect is exactly what's on screen.
                val viewPort = previewView.awaitViewPort()
                fun group(withStill: Boolean) = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                    .apply { if (withStill) still?.let(::addUseCase) }
                    .apply { if (viewPort != null) setViewPort(viewPort) }
                    .build()
                runCatching { p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group(withStill = true)) }
                    .onSuccess { imageCapture = still }
                    .onFailure { error ->
                        // Some older cameras can't stream and take stills at once: scan without the shutter.
                        Log.w("ParityCamera", "stills unavailable", error)
                        p.unbindAll()
                        runCatching { p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group(withStill = false)) }
                            .onFailure { Log.w("ParityCamera", "camera not started", it) }
                    }
            }
            onDispose {
                job.cancel()
                provider?.unbind(*useCases)
                imageCapture = null
            }
        }
    }

    private inner class Analyzer(
        private val mode: ScanMode,
        private val active: State<Boolean>,
        private val onFrame: State<(OcrFrame) -> Unit>,
        private val onQrCode: State<(String) -> Unit>,
    ) : ImageAnalysis.Analyzer {
        private var lastRun = 0L
        private var lastLogged = 0L
        private var loggedSize = ""

        override fun analyze(image: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            val minInterval = if (mode == ScanMode.QR_ONLY) 60L else 250L
            if (!active.value || stillBusy || now - lastRun < minInterval) {
                image.close()
                return
            }
            lastRun = now
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = try {
                visiblePart(image.toBitmap(), image.cropRect).also { visible ->
                    val size = "${visible.width}x${visible.height} of ${image.width}x${image.height}"
                    if (size != loggedSize) Log.d("ParityCamera", "analysing $size").also { loggedSize = size }
                }
            } finally {
                image.close()
            }
            val input = InputImage.fromBitmap(bitmap, rotation)

            if (mode == ScanMode.QR_ONLY) {
                runCatching { Tasks.await(qrOnly.process(input)) }.getOrNull()
                    ?.mapNotNull { it.rawValue }?.forEach { onQrCode.value(it) }
                return
            }

            val frameId = nextFrameId.getAndIncrement()
            synchronized(frames) { frames[frameId] = bitmap to rotation }
            val textTask = textRecognizer.process(input)
            val barcodeTask = tagBarcodes.process(input)
            // While ML Kit reads, find the tags' outlines (design §6.1).
            val quads = runCatching { TagOutlines.find(bitmap, rotation) }.getOrDefault(emptyList())
            val text = runCatching { Tasks.await(textTask) }.getOrNull()
            val barcodes = runCatching { Tasks.await(barcodeTask) }.getOrNull().orEmpty()
            barcodes.filter { it.format == Barcode.FORMAT_QR_CODE }.mapNotNull { it.rawValue }.forEach { onQrCode.value(it) }
            val productCodes = barcodes.filter { it.format != Barcode.FORMAT_QR_CODE }.mapNotNull { it.rawValue }

            val upright = rotation % 180 == 0
            val width = if (upright) bitmap.width else bitmap.height
            val height = if (upright) bitmap.height else bitmap.width
            val lines = text?.textBlocks.orEmpty().flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(
                        text = line.text,
                        box = box.toBox(),
                        elements = line.elements.mapNotNull { e -> e.boundingBox?.let { OcrElement(e.text, it.toBox()) } },
                    )
                }
            }
            if (now - lastLogged > 2_000 && Log.isLoggable("ParityOcr", Log.VERBOSE)) {
                lastLogged = now
                Log.v("ParityOcr", "live ${width}x$height ($script): ${lines.joinToString(" | ") { it.text }} · tags ${quads.map { it.bounds }}")
            }
            onFrame.value(OcrFrame(lines, width, height, productCodes, frameId, quads))
        }
    }

    /**
     * The part of a frame the preview shows. The camera sees more than the screen (the preview
     * fills it by cropping the sides), and text out of view, such as a neighbouring tag or an app's
     * toolbar on a photographed screen, must not be read.
     */
    private fun visiblePart(frame: Bitmap, crop: Rect): Bitmap {
        val visible = Rect(crop)
        if (!visible.intersect(0, 0, frame.width, frame.height) || visible.width() < 16 || visible.height() < 16) return frame
        if (visible.width() == frame.width && visible.height() == frame.height) return frame
        return Bitmap.createBitmap(frame, visible.left, visible.top, visible.width(), visible.height())
    }

    private fun demoBackdrop(): ImageBitmap? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val file = File(context.filesDir, "demo_backdrop.png").takeIf { it.exists() } ?: return null
        return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }

    /** The preview's viewport once it has been laid out, or null if that doesn't happen soon. */
    private suspend fun PreviewView.awaitViewPort(): ViewPort? {
        viewPort?.let { return it }
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                        val port = viewPort ?: return
                        removeOnLayoutChangeListener(this)
                        if (cont.isActive) cont.resume(port)
                    }
                }
                addOnLayoutChangeListener(listener)
                cont.invokeOnCancellation { post { removeOnLayoutChangeListener(listener) } }
            }
        }
    }

    private suspend fun cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ runCatching { future.get() }.onSuccess(cont::resume).onFailure(cont::resumeWithException) }, ContextCompat.getMainExecutor(context))
    }

    /** Still or photo [frameId] (kept upright), or null for a live frame. */
    private fun uprightImage(frameId: Long): Bitmap? = synchronized(photos) { photos[frameId] }

    /** The upright crop of [region] from frame [frameId], and its offset in the frame. */
    private fun crop(frameId: Long, region: Box): Triple<Bitmap, Int, Int>? {
        val upright = uprightImage(frameId) ?: return null
        val left = region.left.toInt().coerceIn(0, upright.width - 1)
        val top = region.top.toInt().coerceIn(0, upright.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, upright.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, upright.height)
        if (right - left < 8 || bottom - top < 8) return null
        return Triple(Bitmap.createBitmap(upright, left, top, right - left, bottom - top), left, top)
    }

    override suspend fun readRegion(frameId: Long, region: Box, languages: String, textHeight: Float?): String? {
        val (bitmap, _, _) = crop(frameId, region) ?: return null
        return tesseract.read(bitmap, languages, textHeight)
    }

    override suspend fun readLines(frameId: Long, region: Box, languages: String, textHeight: Float?, quad: TagQuad?): List<OcrLine>? {
        // The tag alone and straightened, its lines placed back where they are in the frame.
        if (quad != null) {
            val image = uprightImage(frameId) ?: return null
            val tag = withContext(Dispatchers.Default) { TagOutlines.straighten(image, quad, TESSERACT_SIDE) }
            if (tag != null) {
                return tesseract.readLines(tag.bitmap, languages, textHeight?.let { it * tag.scale })?.map { (text, r) ->
                    OcrLine(text, tag.toImage(r.toBox()))
                }.also { tag.bitmap.recycle() }
            }
        }
        val (bitmap, dx, dy) = crop(frameId, region) ?: return null
        return tesseract.readLines(bitmap, languages, textHeight)?.map { (text, r) ->
            OcrLine(text, Box((r.left + dx).toFloat(), (r.top + dy).toFloat(), (r.right + dx).toFloat(), (r.bottom + dy).toFloat()))
        }
    }

    override suspend fun scanImage(bytes: ByteArray): OcrFrame? = withContext(Dispatchers.Default) {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        readPhoto(upright(decoded, bytes))
    }

    override suspend fun capture(stillOnly: Boolean): OcrFrame? {
        val still = imageCapture ?: return if (stillOnly) null else latestFrame()
        stillBusy = true
        try {
            return takeStill(still)
        } finally {
            stillBusy = false
        }
    }

    private suspend fun takeStill(still: ImageCapture): OcrFrame? {
        val image = suspendCancellableCoroutine<ImageProxy?> { cont ->
            still.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cont.isActive) cont.resume(image) else image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w("ParityCamera", "capture failed", exception)
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = try {
                val full = image.toBitmap()
                val visible = visiblePart(full, image.cropRect)
                if (visible !== full) full.recycle()
                val rotation = image.imageInfo.rotationDegrees
                if (rotation == 0) visible else {
                    Bitmap.createBitmap(visible, 0, 0, visible.width, visible.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                        .also { if (it !== visible) visible.recycle() }
                }
            } finally {
                image.close()
            }
            // Up to 3000 px: stills are for small print that live frames are too coarse for.
            readPhoto(scaledToFit(bitmap, 3000))
        }
    }

    /** The newest camera frame, upright, read like a photo: the shutter's fallback where stills can't be taken. */
    private suspend fun latestFrame(): OcrFrame? = withContext(Dispatchers.Default) {
        // Frame ids only grow, so the highest is the newest (the map is ordered by access).
        val (frame, rotation) = synchronized(frames) { frames.entries.maxByOrNull { it.key }?.value } ?: return@withContext null
        val upright = if (rotation == 0) frame else {
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        readPhoto(upright)
    }

    /** Large stills are scaled down so the long side is at most [maxSide]: plenty for OCR, and quicker. */
    private fun scaledToFit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Reads an upright photo like a camera frame and keeps it for re-reading regions. */
    private suspend fun readPhoto(bitmap: Bitmap): OcrFrame {
        val frameId = nextFrameId.getAndIncrement()
        synchronized(photos) { photos[frameId] = bitmap }
        val input = InputImage.fromBitmap(bitmap, 0)
        val textTask = textRecognizer.process(input)
        val barcodeTask = tagBarcodes.process(input)
        val quads = runCatching { TagOutlines.find(bitmap) }.getOrDefault(emptyList())
        val text = runCatching { textTask.awaitResult() }.getOrNull()
        val barcodes = runCatching { barcodeTask.awaitResult() }.getOrNull().orEmpty()
        val lines = text?.textBlocks.orEmpty().flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.toBox(), line.elements.mapNotNull { e -> e.boundingBox?.let { OcrElement(e.text, it.toBox()) } })
            }
        }
        if (Log.isLoggable("ParityOcr", Log.VERBOSE)) {
            Log.v("ParityOcr", "photo ${bitmap.width}x${bitmap.height}: ${lines.joinToString(" | ") { "${it.text} @${it.box}" }} · tags ${quads.map { it.bounds }}")
            runCatching { File(context.cacheDir, "photo_last.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return OcrFrame(
            lines = lines,
            width = bitmap.width,
            height = bitmap.height,
            barcodes = barcodes.filter { it.format != Barcode.FORMAT_QR_CODE }.mapNotNull { it.rawValue },
            id = frameId,
            tagQuads = quads,
        )
    }

    /** Photos from the gallery carry their orientation in EXIF; rotate so text is upright. */
    private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private companion object {
        /** Longest side of a straightened tag handed to Tesseract. */
        const val TESSERACT_SIDE = 2400
    }
}
