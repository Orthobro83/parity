package app.parity.shared.app

import app.parity.core.money.Countries
import app.parity.shared.platform.LocationFix
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Country banner shown when Parity notices a new country (design §5). */
data class CountryBanner(val countryCode: String, val previousCountry: String?)

/** Foreground-only location checks on start, resume, and periodically while scanning (design §5). */
class LocationController(private val graph: AppGraph) {
    private val _lastFix = MutableStateFlow<LocationFix?>(null)
    val lastFix: StateFlow<LocationFix?> = _lastFix

    private val _banner = MutableStateFlow<CountryBanner?>(null)
    val banner: StateFlow<CountryBanner?> = _banner

    private var job: Job? = null

    init {
        // Check again as soon as location access is granted.
        graph.scope.launch { graph.platform.permissions.location.collect { granted -> if (granted) refresh() } }
    }

    fun refresh() {
        if (job?.isActive == true) return
        job = graph.scope.launch {
            val fix = runCatching { graph.platform.location.current() }.getOrNull() ?: return@launch
            _lastFix.value = fix
            val country = fix.countryCode?.uppercase() ?: return@launch
            val settings = graph.settingsRepository.current()
            if (settings.detectedCountry != country) {
                graph.settingsRepository.update { it.copy(detectedCountry = country) }
                // Only announce when the detected country actually drives the local currency.
                if (settings.manualCountry == null && settings.localCurrencyOverride == null && settings.onboarded) {
                    _banner.value = CountryBanner(country, settings.detectedCountry)
                }
            }
        }
    }

    fun dismissBanner() {
        _banner.value = null
    }

    /** "Undo" on the banner: keep using the previous country until the user changes it. */
    fun undoBanner() {
        val banner = _banner.value ?: return
        _banner.value = null
        graph.scope.launch {
            graph.settingsRepository.update { it.copy(manualCountry = banner.previousCountry) }
        }
    }

    fun bannerText(banner: CountryBanner): String {
        val currency = Countries.currencyFor(banner.countryCode)?.code ?: "?"
        return "Looks like you're in ${Countries.name(banner.countryCode)} ${Countries.flag(banner.countryCode)}. Local currency set to $currency."
    }
}
