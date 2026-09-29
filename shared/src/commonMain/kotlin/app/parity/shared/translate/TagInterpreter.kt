package app.parity.shared.translate

import app.parity.core.scan.AiTagReading
import app.parity.shared.data.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Which OpenAI-compatible service reads a label when the shopper taps AI. */
enum class AiPreset(val label: String) {
    /** Grok, served at api.x.ai. The provider name is SpaceXAI; the endpoint is xAI's. */
    SPACEXAI("SpaceXAI"),
    OPENAI("OpenAI"),
    CUSTOM("Custom"),
    ;

    companion object {
        fun fromName(name: String?): AiPreset = entries.firstOrNull { it.name == name } ?: SPACEXAI
    }
}

/** Where one chat-completions request goes. [lowReasoning] is only for SpaceXAI's reasoning models. */
data class AiEndpoint(val baseUrl: String, val model: String, val lowReasoning: Boolean) {
    companion object {
        fun resolve(settings: Settings): AiEndpoint? = when (AiPreset.fromName(settings.aiPreset)) {
            AiPreset.SPACEXAI -> AiEndpoint("https://api.x.ai/v1", "grok-4.7", lowReasoning = true)
            AiPreset.OPENAI -> AiEndpoint("https://api.openai.com/v1", settings.aiModel?.trim()?.ifEmpty { null } ?: "gpt-4o", lowReasoning = false)
            AiPreset.CUSTOM -> {
                val url = baseUrl(settings.aiBaseUrl) ?: return null
                val model = settings.aiModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                AiEndpoint(url, model, lowReasoning = false)
            }
        }

        /** An https origin the user typed, without a trailing slash or a pasted /chat/completions. */
        fun baseUrl(raw: String?): String? {
            var url = raw?.trim()?.trimEnd('/') ?: return null
            if (!url.startsWith("https://")) return null
            if ('@' in url.substringAfter("://")) return null
            if (url.endsWith("/chat/completions")) url = url.removeSuffix("/chat/completions").trimEnd('/')
            return url.takeIf { it.length > "https://".length }
        }
    }
}

/** Whether a key reached the service. [detail] is safe to show; it never includes the key. */
data class AiReachability(val ok: Boolean, val detail: String)

/**
 * One OpenAI-compatible chat completion, used for SpaceXAI (api.x.ai), OpenAI, or a custom base URL.
 * A label crop is sent only when the shopper taps AI. A failure leaves the on-device reading as it was.
 */
class TagInterpreter(http: HttpClient? = null) {
    private val client = http ?: HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 20_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A text-only request, so Settings can check the key without sending a label. */
    suspend fun ping(endpoint: AiEndpoint, apiKey: String): AiReachability {
        val response = post(endpoint, apiKey, messages(text = "Reply with the single word ok.")) ?: return AiReachability(false, "Couldn't reach that service")
        return when (response.status) {
            in 200..299 -> AiReachability(true, "Connected")
            401, 403 -> AiReachability(false, "That key was rejected")
            429 -> AiReachability(false, "That service is limiting this key right now")
            else -> AiReachability(false, "Couldn't reach that service (${response.status})")
        }
    }

    /**
     * Reads one cropped label. Null on any failure, including a timeout, a refusal, or an answer that
     * isn't JSON. The caller shows the answer and learns it only after the shopper confirms it.
     */
    suspend fun read(
        endpoint: AiEndpoint,
        apiKey: String,
        jpeg: ByteArray,
        country: String?,
        currency: String,
        language: String?,
    ): AiTagReading? {
        val prompt = prompt(country, currency, language)
        val image = "data:image/jpeg;base64,${Base64.Default.encode(jpeg)}"
        val response = post(endpoint, apiKey, messages(prompt, image)) ?: return null
        if (!response.ok) return null
        val text = messageText(response.body) ?: return null
        return parse(text)
    }

    private suspend fun post(endpoint: AiEndpoint, apiKey: String, messages: JsonArray): HttpReply? {
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("messages", messages)
            if (endpoint.lowReasoning) put("reasoning_effort", "low")
        }
        val call = runCatching {
            client.post("${endpoint.baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        }.getOrNull() ?: return null
        val body = runCatching { call.bodyAsText() }.getOrNull()
        return HttpReply(call.status.value, body)
    }

    private fun messages(text: String, imageDataUrl: String? = null): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            if (imageDataUrl == null) {
                put("content", text)
            } else {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", imageDataUrl) })
                    })
                })
            }
        })
    }

    private fun prompt(country: String?, currency: String, language: String?) = """
        You read one supermarket shelf price tag from a photo. Reply with one JSON object and no other text.
        name: the product as printed, including brand and size. Not the store, not a slogan, not a translation. null if you cannot see it.
        promo: true only when the price is reduced (a sale, was/now). A multi-buy such as 2X${'$'}5.95 is not a sale by itself.
        quantity: the count in a multi-buy (the 2 in 2X${'$'}5.95), or null.
        amount: the price digits with a dot and no currency sign. For a multi-buy, the amount printed with it. null if you cannot see a price.
        amountIs: "total" when amount pays for quantity items, otherwise "unit".
        was: the crossed-out previous price digits, or null. Never invent one.
        chrome: store name and slogans on the tag that are not the product.
        evidence: short quotes copied from the tag that support the name and the amount.
        These priors can be wrong: country ${country ?: "unknown"}, currency $currency, label language ${language ?: "unknown"}.
        Do not invent text that is not visible. Use null when unsure.
    """.trimIndent()

    private fun messageText(body: String?): String? {
        val root = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return null
        val content = message["content"] ?: return null
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.joinToString("") { part ->
                val obj = runCatching { part.jsonObject }.getOrNull()
                obj?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun parse(raw: String): AiTagReading? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        fun text(key: String) = runCatching {
            obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
        }.getOrNull()
        fun list(key: String): List<String> {
            val element = obj[key] ?: return emptyList()
            val array = element as? JsonArray ?: return listOfNotNull(text(key))
            return array.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } }
        }
        return AiTagReading(
            name = text("name"),
            promo = obj["promo"]?.jsonPrimitive?.let { it.contentOrNull.equals("true", true) } ?: false,
            quantity = obj.int("quantity"),
            amount = text("amount"),
            amountIs = text("amountIs"),
            was = text("was"),
            chrome = list("chrome"),
            evidence = list("evidence"),
        )
    }

    private fun JsonObject.int(key: String): Int? = runCatching {
        this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
    }.getOrNull()

    private class HttpReply(val status: Int, val body: String?) {
        val ok: Boolean get() = status in 200..299
    }
}
