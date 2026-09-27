package app.parity.shared.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.parity.core.money.CurrencyCode
import app.parity.shared.app.AppGraph
import app.parity.shared.data.Settings
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.theme.Parity

/** First launch: your currency, your language, location (design §3). */
@Composable
fun OnboardingScreen(graph: AppGraph, settings: Settings) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var base by rememberSaveable { mutableStateOf(settings.baseCurrency.code) }
    var language by rememberSaveable { mutableStateOf(settings.language) }
    var chooseCountry by rememberSaveable { mutableStateOf(false) }
    val locationGranted by graph.platform.permissions.location.collectAsState()
    val c = Parity.colors

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                Box(Modifier.height(4.dp).width(if (i == step) 28.dp else 12.dp).clip(CircleShape).background(if (i <= step) c.accent else c.outline))
            }
        }
        Spacer(Modifier.height(20.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()).togetherWith(fadeOut()) },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { current ->
            Column {
                when (current) {
                    0 -> {
                        Text("Welcome to Parity", style = Parity.type.headline)
                        Text("Which currency do you think in? Fiat or crypto.", style = Parity.type.body, color = c.textSecondary)
                        Spacer(Modifier.height(16.dp))
                        CurrencyList(CurrencyCode(base), { base = it.code })
                    }
                    1 -> {
                        Text("Your language", style = Parity.type.headline)
                        Text("Product names are translated into it.", style = Parity.type.body, color = c.textSecondary)
                        Spacer(Modifier.height(16.dp))
                        LanguageList(language, { language = it })
                    }
                    else -> {
                        Text("Where are you shopping?", style = Parity.type.headline)
                        Text(
                            "With location on, Parity notices when you arrive in a new country and switches the local currency. " +
                                "It only checks while the app is open.",
                            style = Parity.type.body, color = c.textSecondary,
                        )
                        Spacer(Modifier.height(20.dp))
                        if (locationGranted) {
                            Text("Location is on ✓", style = Parity.type.title, color = c.up)
                        } else {
                            PillButton("Allow location", { graph.platform.permissions.requestLocation() }, Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(12.dp))
                        if (chooseCountry) {
                            CountryList(settings.manualCountry, { graph.settingsController.setManualCountry(it); chooseCountry = false })
                        } else {
                            PillButton(
                                if (settings.manualCountry != null) "Country: ${settings.manualCountry}" else "Set the country myself",
                                { chooseCountry = true }, Modifier.fillMaxWidth(), primary = false,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) PillButton("Back", { step-- }, Modifier.weight(1f), primary = false, height = 52.dp)
            PillButton(
                if (step < 2) "Continue" else "Start scanning",
                {
                    if (step < 2) step++ else graph.settingsController.completeOnboarding(CurrencyCode(base), language)
                },
                Modifier.weight(1f),
            )
        }
    }
}
