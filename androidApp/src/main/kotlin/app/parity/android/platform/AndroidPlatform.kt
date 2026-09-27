package app.parity.android.platform

import android.content.Context
import app.parity.shared.platform.CameraScanner
import app.parity.shared.platform.FileService
import app.parity.shared.platform.Haptics
import app.parity.shared.platform.LocationService
import app.parity.shared.platform.Permissions
import app.parity.shared.platform.Platform
import app.parity.shared.platform.QrEncoder
import app.parity.shared.platform.TextServices

class AndroidPlatform(context: Context, bridge: ActivityBridge) : Platform {
    private val app = context.applicationContext
    override val camera: CameraScanner = CameraScannerImpl(app, bridge, TesseractReader(app))
    override val text: TextServices = MlKitTextServices()
    override val location: LocationService = AndroidLocationService(app, bridge)
    override val permissions: Permissions = bridge
    override val files: FileService = bridge
    override val qr: QrEncoder = ZxingQrEncoder()
    override val haptics: Haptics = AndroidHaptics(app)
}
