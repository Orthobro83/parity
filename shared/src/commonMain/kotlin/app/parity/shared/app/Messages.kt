package app.parity.shared.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** A snackbar message, optionally with an action such as Undo. */
data class Message(val text: String, val actionLabel: String? = null, val action: (suspend () -> Unit)? = null)

/** App-wide snackbar queue. */
class Messages {
    private val channel = Channel<Message>(Channel.BUFFERED)
    val flow = channel.receiveAsFlow()

    fun show(text: String, actionLabel: String? = null, action: (suspend () -> Unit)? = null) {
        channel.trySend(Message(text, actionLabel, action))
    }
}
