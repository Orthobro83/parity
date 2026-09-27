package app.parity.shared.app

import app.parity.core.money.CurrencyCode
import app.parity.shared.data.BackupContents
import app.parity.shared.data.BackupException
import app.parity.shared.data.RestoreMode
import app.parity.shared.data.Settings
import app.parity.shared.rates.RateProviderId
import app.parity.shared.rates.RateResult
import app.parity.shared.util.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Settings screen and onboarding actions (design §3, §12, §15). */
class SettingsController(private val graph: AppGraph) {
    private val scope = graph.scope

    private val _rate = MutableStateFlow<RateResult?>(null)
    val rate: StateFlow<RateResult?> = _rate

    private val _pendingRestore = MutableStateFlow<BackupContents?>(null)
    val pendingRestore: StateFlow<BackupContents?> = _pendingRestore

    private fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settingsRepository.update(transform) }
    }

    fun setBaseCurrency(code: CurrencyCode) = update { it.copy(baseCurrency = code) }
    fun setLanguage(tag: String) = update { it.copy(language = tag) }
    fun setManualCountry(country: String?) = update { it.copy(manualCountry = country) }
    fun setLocalCurrencyOverride(code: CurrencyCode?) = update { it.copy(localCurrencyOverride = code) }
    fun setProvider(provider: RateProviderId) = update { it.copy(rateProvider = provider) }
    fun setApiKey(key: String) = update { it.copy(rateApiKey = key.trim().ifEmpty { null }) }
    fun setTrueBlack(on: Boolean) = update { it.copy(trueBlack = on) }

    fun completeOnboarding(base: CurrencyCode, language: String) {
        scope.launch {
            graph.settingsRepository.update { it.copy(baseCurrency = base, language = language, onboarded = true) }
            graph.location.refresh()
        }
    }

    /** Fetches the current rate for the base → local pair, bypassing the cache. */
    fun refreshRate() {
        scope.launch {
            val s = graph.settingsRepository.current()
            _rate.value = graph.rates.rate(s.baseCurrency, s.localCurrency, s.rateProvider, s.rateApiKey, forceRefresh = true)
        }
    }

    fun exportZip() {
        scope.launch {
            val bytes = graph.backup.exportZip()
            val stamp = Instant.fromEpochMilliseconds(now()).toLocalDateTime(TimeZone.currentSystemDefault()).date
            val saved = graph.platform.files.save("parity-backup-$stamp.zip", "application/zip", bytes)
            if (saved) graph.messages.show("Backup saved")
        }
    }

    fun pickRestoreFile() {
        scope.launch {
            val bytes = graph.platform.files.open(listOf("application/zip", "application/octet-stream")) ?: return@launch
            try {
                _pendingRestore.value = graph.backup.readZip(bytes)
            } catch (e: BackupException) {
                graph.messages.show(e.message ?: "That file couldn't be read")
            }
        }
    }

    fun restore(mode: RestoreMode) {
        val contents = _pendingRestore.value ?: return
        _pendingRestore.value = null
        scope.launch {
            runCatching { graph.backup.restore(contents, mode) }
                .onSuccess { graph.messages.show("Restored: ${contents.summary}") }
                .onFailure { graph.messages.show("Restore failed; nothing was changed. ${it.message ?: ""}") }
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }
}
