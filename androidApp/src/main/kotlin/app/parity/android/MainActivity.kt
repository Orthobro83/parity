package app.parity.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.parity.shared.ui.ParityApp

private val SourceSans3 = FontFamily(
    Font(R.font.source_sans_3_regular, FontWeight.Normal),
    Font(R.font.source_sans_3_semibold, FontWeight.SemiBold),
    Font(R.font.source_sans_3_bold, FontWeight.Bold),
)

class MainActivity : ComponentActivity() {
    private val app get() = application as ParityApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        app.bridge.attach(this)
        setContent { ParityApp(app.graph, SourceSans3) }
    }

    override fun onResume() {
        super.onResume()
        app.bridge.refresh()
        app.graph.location.refresh()
    }

    override fun onDestroy() {
        app.bridge.detach(this)
        super.onDestroy()
    }
}
