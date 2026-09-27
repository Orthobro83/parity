package app.parity.shared.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.parity.core.transfer.PassphraseBox
import app.parity.shared.app.AppGraph
import app.parity.shared.app.SendState
import app.parity.shared.data.ListPayload
import app.parity.shared.platform.QrEncoder
import app.parity.shared.platform.ScanMode
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.components.RoundIconButton
import app.parity.shared.ui.theme.Parity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sender (design §8.5): one static code for "1 of 1", otherwise cycles 1→N at ~8 fps until closed
 * with the round ✕. It can't know when the receiver is done, so closing is the user's job.
 */
@Composable
fun SendScreen(state: SendState, encoder: QrEncoder, onClose: () -> Unit) {
    val matrices = remember(state) { state.codes.map(encoder::encode) }
    var index by remember(state) { mutableIntStateOf(0) }
    var loop by remember(state) { mutableIntStateOf(1) }
    LaunchedEffect(state) {
        if (matrices.size > 1) {
            while (true) {
                delay(125)
                if (index == matrices.lastIndex) loop++
                index = (index + 1) % matrices.size
            }
        }
    }
    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            RoundIconButton(
                Icons.Rounded.Close, "Close", onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                size = 48.dp, background = Color(0xFF0B0D10), tint = Color.White,
            )
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.title, style = Parity.type.headline, color = Color(0xFF0B0D10))
                state.passcode?.let { code ->
                    Spacer(Modifier.height(8.dp))
                    Text("Code for the other phone", style = Parity.type.caption, color = Color(0xFF4A5260))
                    Text(code, style = Parity.type.headline.copy(fontFeatureSettings = "tnum", letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Color(0xFF0B0D10))
                }
                Spacer(Modifier.height(20.dp))
                val matrix = matrices[index]
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val quiet = 4 // quiet zone in modules
                    val cell = size.width / (matrix.size + quiet * 2)
                    for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
                        if (matrix[x, y]) {
                            drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (matrices.size == 1) "1 of 1 · only code needed" else "${index + 1} of ${matrices.size} · loop $loop",
                    style = Parity.type.title, color = Color(0xFF0B0D10),
                )
                Text(
                    "On the other phone, open Parity and point its camera here.",
                    style = Parity.type.caption, color = Color(0xFF4A5260), textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Receiver (design §8.5): shows "Received 2 of 4" and a numbered cell per code; leaves scan mode on
 * its own once every code has arrived and the checksum matches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiveScreen(graph: AppGraph, onClose: () -> Unit) {
    val state by graph.transfer.receive.collectAsState()
    val cameraGranted by graph.platform.permissions.camera.collectAsState()
    val c = Parity.colors
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (cameraGranted) {
                graph.platform.camera.Preview(
                    modifier = Modifier.fillMaxSize(),
                    mode = ScanMode.QR_ONLY,
                    active = true,
                    onFrame = {},
                    onQrCode = graph.transfer::onQrCode,
                )
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Receive by QR", style = Parity.type.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Close, "Stop receiving", onClose, background = c.glass)
                }
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.glass).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        when {
                            state.total == 0 -> "Point the camera at the codes on the other phone"
                            else -> "Received ${state.received.size} of ${state.total}"
                        },
                        style = Parity.type.title, color = c.textPrimary,
                    )
                    if (state.total > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..state.total).forEach { i ->
                                val got = i in state.received
                                val bg by animateColorAsState(if (got) c.up else c.surfaceRaised, label = "cell")
                                Box(Modifier.size(width = 34.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
                                    Text("$i", style = Parity.type.caption, color = if (got) c.onAccent else c.textSecondary)
                                }
                            }
                        }
                        val missing = (1..state.total).filter { it !in state.received }
                        if (missing.isNotEmpty() && state.received.isNotEmpty()) {
                            Text("Waiting for ${missing.take(6).joinToString(", ")}${if (missing.size > 6) "…" else ""}", style = Parity.type.caption, color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
    state.otherTransfer?.let {
        AlertDialog(
            onDismissRequest = graph.transfer::keepCurrent,
            containerColor = c.surfaceRaised,
            shape = RoundedCornerShape(28.dp),
            title = { Text("A different transfer started", style = Parity.type.headline) },
            text = { Text("Start over with the new codes?", style = Parity.type.body) },
            confirmButton = { PillButton("Start over", graph.transfer::startOver, height = 44.dp) },
            dismissButton = { PillButton("Keep current", graph.transfer::keepCurrent, primary = false) },
        )
    }
}

/** Import preview: merge into the current list or create a new one (design §8.4). */
@Composable
fun ImportListDialog(payload: ListPayload, onMerge: () -> Unit, onNewList: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("“${payload.name}”", style = Parity.type.headline) },
        text = {
            Column {
                Text("${payload.items.size} items received", style = Parity.type.body)
                Spacer(Modifier.height(6.dp))
                Text(payload.items.take(8).joinToString(", ") { it.t } + if (payload.items.size > 8) "…" else "", style = Parity.type.caption, color = Parity.colors.textSecondary)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Merge", onMerge, primary = false)
                PillButton("New list", onNewList, height = 44.dp)
            }
        },
        dismissButton = { PillButton("Cancel", onDismiss, primary = false) },
    )
}

/** Asks for the code shown on the sending phone and decrypts the transfer. */
@Composable
fun UnlockDialog(graph: AppGraph) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = graph.transfer::dismissSealed,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Enter the code", style = Parity.type.headline) },
        text = {
            Column {
                Text("Type the code shown above the QR codes on the other phone.", style = Parity.type.body)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(20); wrong = false },
                    singleLine = true,
                    placeholder = { Text("XXXX-XXXX-XXXX") },
                    isError = wrong,
                    supportingText = { if (wrong) Text("That code doesn't open this transfer.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(18.dp),
                    colors = parityTextFieldColors(),
                )
            }
        },
        confirmButton = {
            PillButton(
                if (busy) "Unlocking…" else "Unlock",
                {
                    busy = true
                    scope.launch {
                        wrong = !graph.transfer.unlock(code)
                        busy = false
                    }
                },
                height = 44.dp,
                enabled = !busy && PassphraseBox.isValidCode(code),
            )
        },
        dismissButton = { PillButton("Cancel", graph.transfer::dismissSealed, primary = false) },
    )
}

/** Merge or replace with a backup received by QR (design §15). */
@Composable
fun RestoreDialog(summary: String, onMerge: () -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Restore received data?", style = Parity.type.headline) },
        text = {
            Column {
                Text(summary, style = Parity.type.body)
                Spacer(Modifier.height(8.dp))
                Text("Merge keeps your data and adds anything new. Replace all deletes everything first.", style = Parity.type.caption, color = Parity.colors.textSecondary)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Replace all", onReplace, primary = false, destructive = true)
                PillButton("Merge", onMerge, height = 44.dp)
            }
        },
        dismissButton = { PillButton("Cancel", onDismiss, primary = false) },
    )
}
