package app.parity.android.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.parity.shared.platform.FileService
import app.parity.shared.platform.Permissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Connects app-scoped platform services to whichever activity is currently showing, for things
 * only an activity can do: permission prompts and the system file picker.
 */
class ActivityBridge(private val context: Context) : Permissions, FileService {
    private var activity: ComponentActivity? = null
    private var cameraLauncher: ActivityResultLauncher<String>? = null
    private var locationLauncher: ActivityResultLauncher<Array<String>>? = null
    private var createLauncher: ActivityResultLauncher<String>? = null
    private var openLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null

    private val _camera = MutableStateFlow(granted(Manifest.permission.CAMERA))
    override val camera: StateFlow<Boolean> = _camera

    private val _location = MutableStateFlow(granted(Manifest.permission.ACCESS_COARSE_LOCATION))
    override val location: StateFlow<Boolean> = _location

    val activityOrNull: ComponentActivity? get() = activity

    /** Must be called from the activity's onCreate, before it starts. */
    fun attach(activity: ComponentActivity) {
        this.activity = activity
        cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
        locationLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
        createLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            pendingCreate?.complete(uri)
        }
        openLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingOpen?.complete(uri)
        }
    }

    fun detach(activity: ComponentActivity) {
        if (this.activity === activity) {
            this.activity = null
            pendingCreate?.complete(null)
            pendingOpen?.complete(null)
        }
    }

    fun refresh() {
        _camera.value = granted(Manifest.permission.CAMERA)
        _location.value = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun requestCamera() {
        cameraLauncher?.launch(Manifest.permission.CAMERA)
    }

    override fun requestLocation() {
        locationLauncher?.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean {
        val launcher = createLauncher ?: return false
        val result = CompletableDeferred<Uri?>().also { pendingCreate = it }
        launcher.launch(suggestedName)
        val uri = result.await() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
        }
    }

    override suspend fun open(mimeTypes: List<String>): ByteArray? {
        val launcher = openLauncher ?: return null
        val result = CompletableDeferred<Uri?>().also { pendingOpen = it }
        launcher.launch(mimeTypes.toTypedArray())
        val uri = result.await() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
    }
}
