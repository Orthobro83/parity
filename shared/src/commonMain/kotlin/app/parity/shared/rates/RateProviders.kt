package app.parity.shared.rates

import app.parity.core.fx.FxRate
import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyMath
import app.parity.core.money.divideMoney
import app.parity.core.money.parseDecimal
import app.parity.shared.util.now
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Rate services the user can choose in Settings (design §12). */
enum class RateProviderId(
    val label: String,
    val needsKey: Boolean,
    val supportsCrypto: Boolean,
    /** How often the provider publishes a new rate; also the cache lifetime. */
    val updateIntervalMs: Long,
    val resolution: String,
    val note: String,
) {
    FAWAZ("Currency API (fawazahmed0)", false, true, 6 * HOUR, "Daily", "Free, no key, 300+ currencies incl. GEL, BTC, ZEC"),
    COINGECKO("CoinGecko", false, true, MINUTE, "~1–5 min", "Best for crypto; optional free demo key"),
    OPEN_ER("open.er-api.com", false, false, 6 * HOUR, "Daily", "Free, no key; fiat only"),
    FRANKFURTER("Frankfurter (ECB)", false, false, 6 * HOUR, "Daily (ECB working days)", "Official ECB rates; no GEL"),
    OPEN_EXCHANGE_RATES("Open Exchange Rates", true, false, HOUR, "Hourly (free tier)", "Needs an App ID"),
    EXCHANGERATE_HOST("exchangerate.host", true, false, HOUR, "Plan-dependent", "Needs an access key"),
    ;

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: FAWAZ
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

class RateUnavailableException(message: String) : Exception(message)

/** Fetches one direct rate: 1 [base] = x [quote]. Throws when the provider can't quote the pair. */
internal class RateFetcher(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(provider: RateProviderId, base: CurrencyCode, quote: CurrencyCode, apiKey: String?): FxRate = when (provider) {
        RateProviderId.FAWAZ -> fawaz(base, quote)
        RateProviderId.COINGECKO -> coinGecko(base, quote, apiKey)
        RateProviderId.OPEN_ER -> openEr(base, quote)
        RateProviderId.FRANKFURTER -> frankfurter(base, quote)
        RateProviderId.OPEN_EXCHANGE_RATES -> openExchangeRates(base, quote, apiKey)
        RateProviderId.EXCHANGERATE_HOST -> exchangerateHost(base, quote, apiKey)
    }

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject {
        val response = http.get(url) { headers.forEach { (k, v) -> header(k, v) } }
        if (!response.status.isSuccess()) throw RateUnavailableException("HTTP ${response.status.value} from $url")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonElement?.decimalOrNull(): BigDecimal? = this?.jsonPrimitive?.content?.let(::parseDecimal)

    private fun rate(base: CurrencyCode, quote: CurrencyCode, value: BigDecimal?, publishedAt: Long?, provider: RateProviderId): FxRate {
        if (value == null || value.signum() <= 0) throw RateUnavailableException("${provider.label} has no $base→$quote rate")
        return FxRate(base, quote, value, publishedAt, now(), provider.name)
    }

    private fun dayStart(date: String?): Long? = runCatching {
        LocalDate.parse(date!!).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }.getOrNull()

    private suspend fun fawaz(base: CurrencyCode, quote: CurrencyCode): FxRate {
        val b = base.code.lowercase()
        val body = runCatching {
            getJson("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/$b.json")
        }.getOrElse { getJson("https://latest.currency-api.pages.dev/v1/currencies/$b.json") }
        val value = body[b]?.jsonObject?.get(quote.code.lowercase()).decimalOrNull()
        return rate(base, quote, value, dayStart(body["date"]?.jsonPrimitive?.content), RateProviderId.FAWAZ)
    }

    private suspend fun coinGecko(base: CurrencyCode, quote: CurrencyCode, apiKey: String?): FxRate {
        val headers = apiKey?.takeIf { it.isNotBlank() }?.let { mapOf("x-cg-demo-api-key" to it) } ?: emptyMap()
        val baseCoin = Currencies[base].coinGeckoId
        val quoteCoin = Currencies[quote].coinGeckoId
        suspend fun price(coin: String, vs: CurrencyCode): Pair<BigDecimal?, Long?> {
            val body = getJson(
                "https://api.coingecko.com/api/v3/simple/price?ids=$coin&vs_currencies=${vs.code.lowercase()}&include_last_updated_at=true",
                headers,
            )
            val entry = body[coin]?.jsonObject
            return entry?.get(vs.code.lowercase()).decimalOrNull() to entry?.get("last_updated_at")?.jsonPrimitive?.longOrNull?.times(1000)
        }
        return when {
            baseCoin != null -> price(baseCoin, quote).let { (v, t) -> rate(base, quote, v, t, RateProviderId.COINGECKO) }
            quoteCoin != null -> price(quoteCoin, base).let { (v, t) ->
                rate(base, quote, v?.let { BigDecimal.ONE.divideMoney(it) }, t, RateProviderId.COINGECKO)
            }
            else -> {
                // Fiat↔fiat through bitcoin's price in both currencies.
                val (inBase, t1) = price("bitcoin", base)
                val (inQuote, _) = price("bitcoin", quote)
                val value = if (inBase != null && inQuote != null) inQuote.divideMoney(inBase) else null
                rate(base, quote, value, t1, RateProviderId.COINGECKO)
            }
        }
    }

    private suspend fun openEr(base: CurrencyCode, quote: CurrencyCode): FxRate {
        val body = getJson("https://open.er-api.com/v6/latest/${base.code}")
        if (body["result"]?.jsonPrimitive?.content != "success") throw RateUnavailableException("open.er-api.com: ${body["error-type"]}")
        val value = body["rates"]?.jsonObject?.get(quote.code).decimalOrNull()
        val published = body["time_last_update_unix"]?.jsonPrimitive?.longOrNull?.times(1000)
        return rate(base, quote, value, published, RateProviderId.OPEN_ER)
    }

    private suspend fun frankfurter(base: CurrencyCode, quote: CurrencyCode): FxRate {
        val body = getJson("https://api.frankfurter.dev/v1/latest?base=${base.code}&symbols=${quote.code}")
        val value = body["rates"]?.jsonObject?.get(quote.code).decimalOrNull()
        return rate(base, quote, value, dayStart(body["date"]?.jsonPrimitive?.content), RateProviderId.FRANKFURTER)
    }

    private suspend fun openExchangeRates(base: CurrencyCode, quote: CurrencyCode, apiKey: String?): FxRate {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw RateUnavailableException("Open Exchange Rates needs an App ID")
        // The free plan only quotes against USD, so cross through it.
        val body = getJson("https://openexchangerates.org/api/latest.json?app_id=$key")
        val rates = body["rates"]?.jsonObject ?: throw RateUnavailableException("Open Exchange Rates: no rates")
        val usdToBase = if (base == CurrencyCode.USD) BigDecimal.ONE else rates[base.code].decimalOrNull()
        val usdToQuote = if (quote == CurrencyCode.USD) BigDecimal.ONE else rates[quote.code].decimalOrNull()
        val value = if (usdToBase != null && usdToQuote != null) usdToQuote.divideMoney(usdToBase) else null
        val published = body["timestamp"]?.jsonPrimitive?.longOrNull?.times(1000)
        return rate(base, quote, value, published, RateProviderId.OPEN_EXCHANGE_RATES)
    }

    private suspend fun exchangerateHost(base: CurrencyCode, quote: CurrencyCode, apiKey: String?): FxRate {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw RateUnavailableException("exchangerate.host needs an access key")
        val body = getJson("https://api.exchangerate.host/live?access_key=$key&source=USD&currencies=${base.code},${quote.code}")
        val quotes = body["quotes"]?.jsonObject ?: throw RateUnavailableException("exchangerate.host: no quotes")
        val usdToBase = if (base == CurrencyCode.USD) BigDecimal.ONE else quotes["USD${base.code}"].decimalOrNull()
        val usdToQuote = if (quote == CurrencyCode.USD) BigDecimal.ONE else quotes["USD${quote.code}"].decimalOrNull()
        val value = if (usdToBase != null && usdToQuote != null) usdToQuote.multiply(BigDecimal.ONE.divideMoney(usdToBase), MoneyMath) else null
        val published = body["timestamp"]?.jsonPrimitive?.longOrNull?.times(1000)
        return rate(base, quote, value, published, RateProviderId.EXCHANGERATE_HOST)
    }
}
