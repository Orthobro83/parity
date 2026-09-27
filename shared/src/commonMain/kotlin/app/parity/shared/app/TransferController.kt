package app.parity.shared.app

import app.parity.core.transfer.QrReassembler
import app.parity.core.transfer.QrTransfer
import app.parity.core.transfer.PassphraseBox
import app.parity.core.transfer.deflate
import app.parity.core.transfer.inflate
import app.parity.shared.data.BackupContents
import app.parity.shared.data.BackupPayload
import app.parity.shared.data.ListPayload
import app.parity.shared.data.RestoreMode
import app.parity.shared.data.TransferPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** What the sender screen shows: the codes to cycle through, a title, and the unlock code if encrypted. */
data class SendState(val title: String, val codes: List<String>, val passcode: String? = null)

data class ReceiveState(
    val received: Set<Int> = emptySet(),
    val total: Int = 0,
    /** A code from another transfer appeared; the user decides whether to start over. */
    val otherTransfer: QrTransfer.Frame? = null,
)

/** QR send and receive screens (design §8.4–8.5). */
class TransferController(private val graph: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val reassembler = QrReassembler()

    private val _send = MutableStateFlow<SendState?>(null)
    val send: StateFlow<SendState?> = _send

    private val _receiving = MutableStateFlow(false)
    val receiving: StateFlow<Boolean> = _receiving

    private val _receive = MutableStateFlow(ReceiveState())
    val receive: StateFlow<ReceiveState> = _receive

    /** A received payload waiting for the user to choose how to import it. */
    private val _pendingImport = MutableStateFlow<TransferPayload?>(null)
    val pendingImport: StateFlow<TransferPayload?> = _pendingImport

    /** An encrypted transfer waiting for the code from the sending phone. */
    private val _pendingSealed = MutableStateFlow<ByteArray?>(null)
    val pendingSealed: StateFlow<ByteArray?> = _pendingSealed

    /** A decrypted backup waiting for Merge or Replace all. */
    private val _pendingBackup = MutableStateFlow<BackupContents?>(null)
    val pendingBackup: StateFlow<BackupContents?> = _pendingBackup

    /** Fires when a transfer completes, so the receiver leaves scan mode and returns Home. */
    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val completed: SharedFlow<Unit> = _completed

    // --- Sending ---------------------------------------------------------------------------------

    fun sendActiveList() {
        graph.scope.launch {
            val listId = graph.listController.activeListIdOrNull() ?: graph.lists.ensureList().id
            val payload: TransferPayload = graph.lists.toPayload(listId)
            val bytes = json.encodeToString(TransferPayload.serializer(), payload).encodeToByteArray()
            _send.value = SendState((payload as ListPayload).name, QrTransfer.encode(bytes))
        }
    }

    /** Everything, encrypted with a code shown on this screen and typed on the other phone (design §8.5). */
    fun sendBackup() {
        graph.scope.launch {
            val code = PassphraseBox.newCode()
            val codes = withContext(Dispatchers.Default) {
                val payload: TransferPayload = BackupPayload(files = graph.backup.exportFiles())
                val bytes = json.encodeToString(TransferPayload.serializer(), payload).encodeToByteArray()
                QrTransfer.encode(PassphraseBox.seal(deflate(bytes), code), encrypted = true)
            }
            _send.value = SendState("All Parity data", codes, passcode = code)
        }
    }

    fun closeSend() {
        _send.value = null
    }

    // --- Receiving -------------------------------------------------------------------------------

    fun startReceiving(firstCode: String? = null) {
        reassembler.reset()
        _receive.value = ReceiveState()
        _receiving.value = true
        firstCode?.let(::onQrCode)
    }

    fun stopReceiving() {
        _receiving.value = false
        reassembler.reset()
        _receive.value = ReceiveState()
    }

    /** Called from the camera thread; handled on the main thread. */
    fun onQrCode(text: String) {
        graph.scope.launch { handle(reassembler.accept(text)) }
    }

    fun startOver() {
        val frame = _receive.value.otherTransfer ?: return
        _receive.value = ReceiveState()
        handle(reassembler.restartWith(frame))
    }

    fun keepCurrent() {
        _receive.value = _receive.value.copy(otherTransfer = null)
    }

    private fun handle(event: QrReassembler.Event) {
        if (!_receiving.value) return
        when (event) {
            QrReassembler.Event.Ignored -> Unit
            is QrReassembler.Event.Progress -> {
                graph.platform.haptics.tick()
                _receive.value = _receive.value.copy(received = event.received, total = event.total)
            }
            is QrReassembler.Event.DifferentTransfer -> {
                if (_receive.value.otherTransfer == null) _receive.value = _receive.value.copy(otherTransfer = event.frame)
            }
            QrReassembler.Event.Corrupt -> {
                _receive.value = ReceiveState()
                graph.messages.show("That transfer didn't check out. Keep the codes in view to try again.")
            }
            is QrReassembler.Event.Complete -> {
                graph.platform.haptics.success()
                _receiving.value = false
                reassembler.reset()
                _receive.value = ReceiveState()
                if (event.encrypted) {
                    _pendingSealed.value = event.payload
                } else {
                    accept(event.payload)
                }
                _completed.tryEmit(Unit)
            }
        }
    }

    private fun accept(bytes: ByteArray) {
        val payload = runCatching { json.decodeFromString(TransferPayload.serializer(), bytes.decodeToString()) }.getOrNull()
        when (payload) {
            is ListPayload -> _pendingImport.value = payload
            is BackupPayload -> runCatching { graph.backup.read(payload.files) }
                .onSuccess { _pendingBackup.value = it }
                .onFailure { graph.messages.show(it.message ?: "That backup couldn't be read.") }
            null -> graph.messages.show("Received a Parity code this version can't read.")
        }
    }

    /** Decrypts a received transfer with the code from the other phone. Returns false for a wrong code. */
    suspend fun unlock(code: String): Boolean {
        val sealed = _pendingSealed.value ?: return false
        val opened = withContext(Dispatchers.Default) { PassphraseBox.open(sealed, code)?.let(::inflate) } ?: return false
        _pendingSealed.value = null
        accept(opened)
        return true
    }

    fun dismissSealed() {
        _pendingSealed.value = null
    }

    fun restoreBackup(mode: RestoreMode) {
        val contents = _pendingBackup.value ?: return
        _pendingBackup.value = null
        graph.scope.launch {
            runCatching { graph.backup.restore(contents, mode) }
                .onSuccess { graph.messages.show("Restored: ${contents.summary}") }
                .onFailure { graph.messages.show("Restore failed; nothing was changed. ${it.message ?: ""}") }
        }
    }

    fun dismissBackup() {
        _pendingBackup.value = null
    }

    /** Imports a received list: merged into the current list, or as a new one (design §8.4). */
    fun importList(merge: Boolean) {
        val payload = _pendingImport.value as? ListPayload ?: return
        _pendingImport.value = null
        graph.scope.launch {
            val target = if (merge) graph.listController.activeListIdOrNull() ?: graph.lists.ensureList().id else null
            val listId = graph.lists.import(payload, target)
            graph.listController.select(listId)
            graph.messages.show("Imported ${payload.items.size} items from “${payload.name}”")
        }
    }

    fun dismissImport() {
        _pendingImport.value = null
    }
}
