package app.parity.shared.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.parity.shared.app.AppGraph
import app.parity.shared.data.ListPayload
import app.parity.shared.data.RestoreMode
import app.parity.shared.data.Settings
import app.parity.shared.ui.screens.AnalyticsScreen
import app.parity.shared.ui.screens.HistoryScreen
import app.parity.shared.ui.screens.HomeScreen
import app.parity.shared.ui.screens.ImportListDialog
import app.parity.shared.ui.screens.ListScreen
import app.parity.shared.ui.screens.OnboardingScreen
import app.parity.shared.ui.screens.ReceiveScreen
import app.parity.shared.ui.screens.RestoreDialog
import app.parity.shared.ui.screens.SendScreen
import app.parity.shared.ui.screens.SessionDetailScreen
import app.parity.shared.ui.screens.SettingsScreen
import app.parity.shared.ui.screens.UnlockDialog
import app.parity.shared.ui.theme.Parity
import app.parity.shared.ui.theme.ParityTheme

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    LIST("List", Icons.Rounded.Checklist),
    HISTORY("History", Icons.Rounded.History),
    ANALYTICS("Analytics", Icons.Rounded.Insights),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

/** Root composable, shared by every platform. The platform supplies the Source Sans 3 font family. */
@Composable
fun ParityApp(graph: AppGraph, fontFamily: FontFamily) {
    val settings by graph.settings.collectAsState()
    ParityTheme(fontFamily = fontFamily, trueBlack = settings?.trueBlack ?: false) {
        Surface(color = Parity.colors.bg, modifier = Modifier.fillMaxSize()) {
            val s = settings
            when {
                s == null -> Box(Modifier.fillMaxSize())
                !s.onboarded -> OnboardingScreen(graph, s)
                else -> MainShell(graph, s)
            }
        }
    }
}

@Composable
private fun MainShell(graph: AppGraph, settings: Settings) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val sending by graph.transfer.send.collectAsState()
    val receiving by graph.transfer.receiving.collectAsState()
    val detail by graph.history.detail.collectAsState()
    val pendingImport by graph.transfer.pendingImport.collectAsState()
    val pendingSealed by graph.transfer.pendingSealed.collectAsState()
    val pendingBackup by graph.transfer.pendingBackup.collectAsState()

    LaunchedEffect(Unit) {
        graph.messages.flow.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                duration = if (message.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
        }
    }
    // A Parity QR code seen by the Home camera switches straight into receive mode (design §8.5).
    LaunchedEffect(Unit) { graph.home.qrDetected.collect { graph.transfer.startReceiving(it) } }
    // When a transfer completes, the receiver returns Home.
    LaunchedEffect(Unit) { graph.transfer.completed.collect { tab = Tab.HOME } }
    LaunchedEffect(Unit) { graph.location.refresh() }

    val overlayOpen = receiving || sending != null || detail != null
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Parity.colors.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { BottomNav(tab) { tab = it } },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                when (tab) {
                    Tab.HOME -> HomeScreen(graph, settings, cameraActive = !overlayOpen)
                    Tab.LIST -> ListScreen(graph)
                    Tab.HISTORY -> HistoryScreen(graph, settings)
                    Tab.ANALYTICS -> AnalyticsScreen(graph, settings)
                    Tab.SETTINGS -> SettingsScreen(graph, settings)
                }
            }
        }
        detail?.let { SessionDetailScreen(it, onClose = graph.history::close) }
        sending?.let { SendScreen(it, graph.platform.qr, onClose = graph.transfer::closeSend) }
        if (receiving) ReceiveScreen(graph, onClose = graph.transfer::stopReceiving)
    }

    (pendingImport as? ListPayload)?.let { payload ->
        ImportListDialog(
            payload = payload,
            onMerge = { graph.transfer.importList(merge = true); tab = Tab.LIST },
            onNewList = { graph.transfer.importList(merge = false); tab = Tab.LIST },
            onDismiss = graph.transfer::dismissImport,
        )
    }

    if (pendingSealed != null) UnlockDialog(graph)
    pendingBackup?.let { contents ->
        RestoreDialog(
            summary = contents.summary,
            onMerge = { graph.transfer.restoreBackup(RestoreMode.MERGE) },
            onReplace = { graph.transfer.restoreBackup(RestoreMode.REPLACE_ALL) },
            onDismiss = graph.transfer::dismissBackup,
        )
    }

    PlatformBackHandler(enabled = overlayOpen || tab != Tab.HOME) {
        when {
            receiving -> graph.transfer.stopReceiving()
            sending != null -> graph.transfer.closeSend()
            detail != null -> graph.history.close()
            else -> tab = Tab.HOME
        }
    }
}

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    val c = Parity.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(color = c.outline, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val tint by animateColorAsState(if (active) c.accent else c.textSecondary, label = "navTint")
                val pill by animateColorAsState(if (active) c.accent.copy(alpha = 0.16f) else Color.Transparent, label = "navPill")
                Column(
                    Modifier
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) }
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.clip(CircleShape).background(pill).padding(horizontal = 18.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Text(tab.label, style = Parity.type.caption, color = tint)
                }
            }
        }
    }
}
