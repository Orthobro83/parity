package app.parity.android

import android.app.Application
import app.parity.android.platform.ActivityBridge
import app.parity.android.platform.AndroidPlatform
import app.parity.shared.app.AppGraph
import app.parity.shared.data.buildParityDatabase

class ParityApplication : Application() {
    lateinit var bridge: ActivityBridge
        private set
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        bridge = ActivityBridge(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "dev"
        graph = AppGraph(AndroidPlatform(this, bridge), buildParityDatabase(this), version)
    }
}
