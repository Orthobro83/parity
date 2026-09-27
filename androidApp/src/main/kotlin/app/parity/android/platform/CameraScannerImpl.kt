package app.parity.android.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.parity.core.scan.Box
import app.parity.core.scan.OcrElement
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.shared.platform.CameraScanner
import app.parity.shared.platform.ScanMode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.camera.core.Preview as CameraPreview

/**
 * CameraX preview plus ML Kit analysis (design §6): Latin text and barcodes for price tags,
 * QR codes for transfers. The latest frame is kept so a name region can be re-read by Tesseract.
 */
class CameraScannerImpl(
    private val context: Context,
    private val bridge: ActivityBridge,
    private val tesseract: TesseractReader,
) : CameraScanner {
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
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

    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastRotation = 0
    @Volatile private var camera: Camera? = null

    @Composable
    override fun Preview(
        modifier: Modifier,
        mode: ScanMode,
        active: Boolean,
        onFrame: (OcrFrame) -> Unit,
        onQrCode: (String) -> Unit,
    ) {
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
                useCases = arrayOf(preview, analysis)
                runCatching {
                    camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                }
            }
            onDispose {
                job.cancel()
                provider?.unbind(*useCases)
                camera = null
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

        override fun analyze(image: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            val minInterval = if (mode == ScanMode.QR_ONLY) 60L else 250L
            if (!active.value || now - lastRun < minInterval) {
                image.close()
                return
            }
            lastRun = now
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = try {
                image.toBitmap()
            } finally {
                image.close()
            }
            val input = InputImage.fromBitmap(bitmap, rotation)

            if (mode == ScanMode.QR_ONLY) {
                runCatching { Tasks.await(qrOnly.process(input)) }.getOrNull()
                    ?.mapNotNull { it.rawValue }?.forEach { onQrCode.value(it) }
                return
            }

            lastFrame = bitmap
            lastRotation = rotation
            val textTask = textRecognizer.process(input)
            val barcodeTask = tagBarcodes.process(input)
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
            onFrame.value(OcrFrame(lines, width, height, productCodes))
        }
    }

    private suspend fun cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ runCatching { future.get() }.onSuccess(cont::resume).onFailure(cont::resumeWithException) }, ContextCompat.getMainExecutor(context))
    }

    override suspend fun readRegion(region: Box, languages: String): String? {
        val frame = lastFrame ?: return null
        val rotation = lastRotation
        val upright = if (rotation == 0) frame else {
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        val left = region.left.toInt().coerceIn(0, upright.width - 1)
        val top = region.top.toInt().coerceIn(0, upright.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, upright.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, upright.height)
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(upright, left, top, right - left, bottom - top)
        return tesseract.read(crop, languages)
    }

    override fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
