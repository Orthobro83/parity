package app.parity.shared.rates

import app.parity.core.fx.FxRate
import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.core.money.toPlain
import app.parity.shared.data.RateCacheDao
import app.parity.shared.data.RateCacheEntity
import app.parity.shared.util.now
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A rate plus whether it came from an out-of-date cache because the network failed. */
data class RateResult(val rate: FxRate?, val stale: Boolean, val error: String? = null)

/**
 * Rates with caching and cross rates (design §12). Fetches at scan time when the cached rate is older
 * than the provider's update interval; falls back to the cache offline.
 */
class RatesRepository(private val cache: RateCacheDao, http: HttpClient? = null) {
    private val client = http ?: HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
        }
    }
    private val fetcher = RateFetcher(client)
    private val mutex = Mutex()

    suspend fun rate(
        base: CurrencyCode,
        quote: CurrencyCode,
        provider: RateProviderId,
        apiKey: String?,
        forceRefresh: Boolean = false,
    ): RateResult = mutex.withLock {
        if (base == quote) return@withLock RateResult(FxRate(base, quote, BigDecimal.ONE, now(), now(), "identity"), false)
        val cached = cache.get(base.code, quote.code, provider.name)?.toRate()
        val fresh = cached != null && now() - cached.fetchedAtMs < provider.updateIntervalMs
        if (fresh && !forceRefresh) return@withLock RateResult(cached, false)

        val fetched = runCatching { fetchWithPivots(base, quote, provider, apiKey) }
        fetched.getOrNull()?.let { rate ->
            cache.upsert(rate.toEntity())
            return@withLock RateResult(rate, false)
        }
        val fallback = cached ?: cache.latestAnyProvider(base.code, quote.code)?.toRate()
        RateResult(fallback, stale = fallback != null, error = fetched.exceptionOrNull()?.message)
    }

    private suspend fun fetchWithPivots(base: CurrencyCode, quote: CurrencyCode, provider: RateProviderId, apiKey: String?): FxRate {
        val crypto = Currencies[base].isCrypto || Currencies[quote].isCrypto
        // Fiat-only providers get crypto legs from CoinGecko (public, no key).
        if (crypto && !provider.supportsCrypto) {
            val cryptoSide = if (Currencies[base].isCrypto) base else quote
            val fiatSide = if (cryptoSide == base) quote else base
            val cryptoUsd = fetcher.fetch(RateProviderId.COINGECKO, cryptoSide, CurrencyCode.USD, null)
            val usdFiat = if (fiatSide == CurrencyCode.USD) null else fetcher.fetch(provider, CurrencyCode.USD, fiatSide, apiKey)
            val cryptoInFiat = usdFiat?.let { cryptoUsd.value.multiply(it.value, MoneyMath) } ?: cryptoUsd.value
            val rate = FxRate(
                cryptoSide, fiatSide, cryptoInFiat,
                publishedAtMs = usdFiat?.publishedAtMs ?: cryptoUsd.publishedAtMs,
                fetchedAtMs = now(), provider = provider.name, pivot = if (usdFiat != null) CurrencyCode.USD else null,
            )
            return if (cryptoSide == base) rate else rate.inverted().copy(pivot = rate.pivot)
        }
        val direct = runCatching { fetcher.fetch(provider, base, quote, apiKey) }
        direct.getOrNull()?.let { return it }
        for (pivot in listOf(CurrencyCode.USD, CurrencyCode.EUR)) {
            if (pivot == base || pivot == quote) continue
            val viaPivot = runCatching {
                val first = fetcher.fetch(provider, base, pivot, apiKey)
                val second = fetcher.fetch(provider, pivot, quote, apiKey)
                FxRate(
                    base, quote, first.value.multiply(second.value, MoneyMath),
                    publishedAtMs = listOfNotNull(first.publishedAtMs, second.publishedAtMs).minOrNull(),
                    fetchedAtMs = now(), provider = provider.name, pivot = pivot,
                )
            }
            viaPivot.getOrNull()?.let { return it }
        }
        throw direct.exceptionOrNull() ?: RateUnavailableException("No rate for $base→$quote")
    }

    private fun RateCacheEntity.toRate() = FxRate(
        CurrencyCode(base), CurrencyCode(quote), decimal(rate), publishedAt, fetchedAt, provider, pivot?.let(::CurrencyCode),
    )

    private fun FxRate.toEntity() = RateCacheEntity(
        base.code, quote.code, provider, value.toPlain(), publishedAtMs, fetchedAtMs, pivot?.code,
    )
}
