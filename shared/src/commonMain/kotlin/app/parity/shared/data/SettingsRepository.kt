package app.parity.shared.data

import app.parity.core.money.Countries
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Languages
import app.parity.shared.rates.RateProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User settings plus the detected location (design §3, §5). */
data class Settings(
    val baseCurrency: CurrencyCode = CurrencyCode.USD,
    val language: String = "en",
    val localCurrencyOverride: CurrencyCode? = null,
    val manualCountry: String? = null,
    val detectedCountry: String? = null,
    val rateProvider: RateProviderId = RateProviderId.FAWAZ,
    val rateApiKey: String? = null,
    val trueBlack: Boolean = false,
    val onboarded: Boolean = false,
    /** Translate product names online when there's no offline pack for the language (design §6.2). */
    val translateOnline: Boolean = true,
    /** The offline pack last downloaded by itself on arriving in a country ("ka>en"). */
    val autoPack: String? = null,
) {
    /** Manual location wins over detection until cleared. */
    val country: String? get() = manualCountry ?: detectedCountry

    val localCurrency: CurrencyCode
        get() = localCurrencyOverride ?: country?.let(Countries::currencyFor) ?: baseCurrency

    /**
     * Main language of shelf labels here, which picks the OCR engine and the translation source.
     * Follows a local currency set by hand, so lari means Georgian labels even outside Georgia.
     */
    val labelLanguage: String? get() = Languages.labelLanguageFor(country, localCurrency)
}

class SettingsRepository(private val dao: SettingsDao) {
    val settings: Flow<Settings> = dao.observe().map { it?.toModel() ?: Settings() }

    suspend fun current(): Settings = dao.get()?.toModel() ?: Settings()

    suspend fun update(transform: (Settings) -> Settings) {
        dao.upsert(transform(current()).toEntity())
    }

    private fun SettingsEntity.toModel() = Settings(
        baseCurrency = CurrencyCode(baseCurrency),
        language = language,
        localCurrencyOverride = localCurrencyOverride?.let(::CurrencyCode),
        manualCountry = manualCountry,
        detectedCountry = detectedCountry,
        rateProvider = RateProviderId.fromName(rateProvider),
        rateApiKey = rateApiKey,
        trueBlack = trueBlack,
        onboarded = onboarded,
        translateOnline = translateOnline,
        autoPack = autoPack,
    )

    private fun Settings.toEntity() = SettingsEntity(
        baseCurrency = baseCurrency.code,
        language = language,
        localCurrencyOverride = localCurrencyOverride?.code,
        manualCountry = manualCountry,
        detectedCountry = detectedCountry,
        rateProvider = rateProvider.name,
        rateApiKey = rateApiKey,
        trueBlack = trueBlack,
        onboarded = onboarded,
        translateOnline = translateOnline,
        autoPack = autoPack,
    )
}
