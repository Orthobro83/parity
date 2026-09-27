package app.parity.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun ParityApp() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D10)), contentAlignment = Alignment.Center) {
        Text("Parity", color = Color(0xFFECEEF1))
    }
}
