package app.parity.android.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.util.Log
import app.parity.shared.platform.Haptics
import app.parity.shared.platform.LocationFix
import app.parity.shared.platform.LocationService
import app.parity.shared.platform.QrEncoder
import app.parity.shared.platform.QrMatrix
import app.parity.shared.platform.TextServices
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "ParityText"

/** On-device language identification and translation with ML Kit (design §6.2). */
class MlKitTextServices : TextServices {
    private val languageId by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build())
    }
    private val translators = mutableMapOf<String, Translator>()

    override suspend fun identifyLanguage(text: String): String? =
        runCatching { languageId.identifyLanguage(text).awaitResult() }.getOrNull()?.takeIf { it != "und" }

    override suspend fun translate(text: String, from: String, to: String): String? {
        val translator = translatorFor(from, to) ?: return null
        // Only with packs already here: downloading one is the shopper's choice (Settings).
        if (!isTranslationReady(from, to)) return null
        return withTimeoutOrNull(20_000) {
            runCatching { translator.translate(text).awaitResult() }
                .onSuccess { Log.d(TAG, "offline $from>$to: $text → $it") }
                .onFailure { Log.w(TAG, "translate $from>$to failed", it) }.getOrNull()
        }
    }

    override suspend fun isTranslationReady(from: String, to: String): Boolean {
        val languages = listOf(from, to).map { TranslateLanguage.fromLanguageTag(it) ?: return false }
        val models = RemoteModelManager.getInstance()
        return languages.all { language ->
            runCatching { models.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).awaitResult() }.getOrDefault(false)
        }.also { Log.d(TAG, "language packs $from>$to ready: $it") }
    }

    override suspend fun prepare(from: String, to: String, wifiOnly: Boolean): Boolean {
        val translator = translatorFor(from, to) ?: return false
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        return runCatching { translator.downloadModelIfNeeded(conditions).awaitResult() }
            .onFailure { Log.w(TAG, "language pack $from>$to not downloaded", it) }
            .isSuccess
    }

    override suspend fun offlineLanguages(): Set<String> = runCatching {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).awaitResult()
            .map { it.language }.toSet()
    }.getOrDefault(emptySet())

    override suspend fun deleteOffline(language: String): Boolean {
        val code = TranslateLanguage.fromLanguageTag(language) ?: return false
        return runCatching {
            RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).awaitResult()
        }.isSuccess
    }

    private fun translatorFor(from: String, to: String): Translator? {
        val source = TranslateLanguage.fromLanguageTag(from) ?: return null
        val target = TranslateLanguage.fromLanguageTag(to) ?: return null
        if (source == target) return null
        return synchronized(translators) {
            translators.getOrPut("$source>$target") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
            }
        }
    }
}

/** Coarse location plus the country, falling back to the mobile network's country (design §5). */
class AndroidLocationService(private val context: Context, private val bridge: ActivityBridge) : LocationService {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission") // checked through bridge.location
    override suspend fun current(): LocationFix? {
        val network = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.uppercase()?.takeIf { it.length == 2 }
        if (!bridge.location.value) return network?.let { LocationFix(it, null, null) }
        val location = runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).awaitResult()
        }.getOrNull() ?: runCatching { fused.lastLocation.awaitResult() }.getOrNull()
        if (location == null) return network?.let { LocationFix(it, null, null) }
        val country = countryAt(location.latitude, location.longitude) ?: network
        return LocationFix(country, location.latitude, location.longitude)
    }

    private suspend fun countryAt(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.US)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                cont.resume(addresses.firstOrNull()?.countryCode)
                            }

                            override fun onError(errorMessage: String?) {
                                cont.resume(null)
                            }
                        })
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.countryCode
            }
        }.getOrNull()?.uppercase()
    }
}

/** QR matrices via ZXing at error correction M; Base45 text selects alphanumeric mode. */
class ZxingQrEncoder : QrEncoder {
    override fun encode(text: String): QrMatrix {
        val code = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.MARGIN to 0))
        val matrix = code.matrix
        val size = matrix.width
        return QrMatrix(size, BooleanArray(size * size) { i -> matrix.get(i % size, i / size).toInt() == 1 })
    }
}

class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun tick() = vibrate(
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE),
    )

    override fun success() = vibrate(
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        else VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE),
    )

    private fun vibrate(effect: VibrationEffect) {
        runCatching { vibrator?.vibrate(effect) }
    }
}
