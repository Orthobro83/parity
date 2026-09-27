package app.parity.shared.app

import app.parity.shared.data.BackupService
import app.parity.shared.data.ListRepository
import app.parity.shared.data.ParityDatabase
import app.parity.shared.data.Settings
import app.parity.shared.data.SettingsRepository
import app.parity.shared.data.ShoppingRepository
import app.parity.shared.platform.Platform
import app.parity.shared.rates.RatesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * App-scoped object graph. Lives in the Application, so state survives activity recreation
 * without a ViewModel library (see PROGRESS.md decisions).
 */
class AppGraph(val platform: Platform, db: ParityDatabase, val appVersion: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(db.settings())
    val rates = RatesRepository(db.rates())
    val shopping = ShoppingRepository(db)
    val lists = ListRepository(db)
    val backup = BackupService(db, appVersion)

    /** Null until the settings row has loaded. */
    val settings: StateFlow<Settings?> = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, null)

    val messages = Messages()
    val location = LocationController(this)
    val listController = ListController(this)
    val home = HomeController(this)
    val history = HistoryController(this)
    val transfer = TransferController(this)
    val settingsController = SettingsController(this)
}
