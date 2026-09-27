package app.parity.shared.ui

import androidx.compose.runtime.Composable

/** System back gesture/button handling; a no-op where the platform has none. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
