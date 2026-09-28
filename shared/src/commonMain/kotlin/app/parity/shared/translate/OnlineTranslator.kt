package app.parity.shared.translate

import app.parity.core.money.Languages
import app.parity.core.scan.TranslationCandidate
import app.parity.core.scan.TranslationPick
import app.parity.shared.util.now
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Translates product names online (design §6.2) with MyMemory's free service: no key or account,
 * and about 5,000 characters a day per network, which is a few hundred product names. Used when
 * there's no offline language pack on the phone. Only the product name is sent.
 */
class OnlineTranslator(http: HttpClient? = null) {
    private val client = http ?: HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 6_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Set when the service says the day's quota is used up, to stop asking for a while. */
    private var pausedUntil = 0L

    /**
     * [text] translated from [from] into [to], or null when there's no network, the quota is used up,
     * or no answer reads as [to]. [identify] names a text's language on the phone, if it can tell.
     */
    suspend fun translate(text: String, from: String, to: String, identify: suspend (String) -> String?): String? {
        if (now() < pausedUntil) return null
        val body = runCatching {
            client.get("https://api.mymemory.translated.net/get") {
                parameter("q", text)
                parameter("langpair", "${code(from)}|${code(to)}")
            }.bodyAsText()
        }.getOrNull() ?: return null
        val response = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val status = response["responseStatus"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (response["quotaFinished"]?.jsonPrimitive?.booleanOrNull == true || status == 429) {
            pausedUntil = now() + 60 * 60_000L
            return null
        }
        if (status != null && status != 200) return null

        val candidates = candidates(response, text)
        val target = Languages.base(to)
        return TranslationPick.rank(text, from, candidates).firstOrNull { answer ->
            // A remembered answer can be in the wrong language ("Susu"); keep it only if it isn't clearly so.
            val language = runCatching { identify(answer) }.getOrNull()
            language == null || Languages.base(language) == target
        }
    }

    private fun candidates(response: JsonObject, text: String): List<TranslationCandidate> {
        val matches = runCatching { response["matches"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { element ->
            val match = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val translation = match["translation"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TranslationCandidate(
                text = translation,
                segment = match["segment"]?.jsonPrimitive?.contentOrNull,
                quality = match["quality"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0,
                match = match["match"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                machine = match["created-by"]?.jsonPrimitive?.contentOrNull == "MT!",
            )
        }
        if (matches.isNotEmpty()) return matches
        // No list of answers: the main answer, treated as a machine translation of the text asked.
        val top = response["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return listOf(TranslationCandidate(top, text, 0, 1.0, machine = true))
    }

    /** MyMemory's language codes: the plain code, and zh-CN for Chinese. */
    private fun code(language: String): String = when (val base = Languages.base(language)) {
        "zh" -> "zh-CN"
        else -> base
    }
}
