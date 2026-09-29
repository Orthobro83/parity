package app.parity.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.parity.core.money.Countries
import app.parity.core.money.Currencies
import app.parity.core.money.Languages
import app.parity.core.fx.formatRate
import app.parity.core.scan.LabelReading
import app.parity.shared.app.AppGraph
import app.parity.shared.data.RestoreMode
import app.parity.shared.data.Settings
import app.parity.shared.rates.RateProviderId
import app.parity.shared.translate.AiPreset
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.components.SectionHeader
import app.parity.shared.ui.formatAge
import app.parity.shared.ui.formatDateTime
import app.parity.shared.ui.theme.Parity
import app.parity.shared.util.now

private enum class Picker { BASE, LANGUAGE, COUNTRY, LOCAL }

/** Settings (design §3, §12, §15). */
@Composable
fun SettingsScreen(graph: AppGraph, settings: Settings) {
    val controller = graph.settingsController
    val rate by controller.rate.collectAsState()
    val pendingRestore by controller.pendingRestore.collectAsState()
    val locationGranted by graph.platform.permissions.location.collectAsState()
    val offlinePacks by controller.offlinePacks.collectAsState()
    val downloadingPack by controller.downloadingPack.collectAsState()
    LaunchedEffect(Unit) { controller.refreshOfflinePacks() }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var apiKey by remember(settings.rateProvider) { mutableStateOf(settings.rateApiKey ?: "") }
    var aiKey by remember(settings.aiApiKey) { mutableStateOf(settings.aiApiKey ?: "") }
    var aiUrl by remember(settings.aiBaseUrl) { mutableStateOf(settings.aiBaseUrl ?: "") }
    var aiModel by remember(settings.aiModel) { mutableStateOf(settings.aiModel ?: "") }
    val aiPreset = AiPreset.fromName(settings.aiPreset)
    val c = Parity.colors

    LaunchedEffect(settings.baseCurrency, settings.localCurrency, settings.rateProvider) { controller.refreshRate() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Settings", style = Parity.type.headline)

        SectionHeader("Currency & language")
        Group {
            SettingRow("Your currency", "${settings.baseCurrency.code} · ${Currencies[settings.baseCurrency].name}") { picker = Picker.BASE }
            SettingRow("Your language", Languages.find(settings.language)?.englishName ?: settings.language) { picker = Picker.LANGUAGE }
        }

        SectionHeader("Location")
        Group {
            val country = settings.country
            SettingRow(
                "Country",
                (country?.let { "${Countries.flag(it)} ${Countries.name(it)}" } ?: "Unknown") +
                    if (settings.manualCountry != null) " · set manually" else if (settings.detectedCountry != null) " · detected" else "",
            ) { picker = Picker.COUNTRY }
            SettingRow(
                "Local currency",
                settings.localCurrency.code + if (settings.localCurrencyOverride != null) " · set manually" else " · from country",
            ) { picker = Picker.LOCAL }
            if (!locationGranted) {
                SettingRow("Location access", "Off — tap to allow, so Parity notices new countries") { graph.platform.permissions.requestLocation() }
            }
            if (settings.manualCountry != null || settings.localCurrencyOverride != null) {
                SettingRow("Use detected location", "Clear the manual country and currency") {
                    controller.setManualCountry(null)
                    controller.setLocalCurrencyOverride(null)
                    graph.location.refresh()
                }
            }
        }

        SectionHeader("Translation")
        Group {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Translate online", style = Parity.type.body)
                    Text(
                        "Product names are sent to MyMemory (translated.net) to translate when there's no offline pack. Nothing else is sent.",
                        style = Parity.type.caption, color = c.textSecondary,
                    )
                }
                Switch(
                    checked = settings.translateOnline,
                    onCheckedChange = controller::setTranslateOnline,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                )
            }
            val label = settings.labelLanguage
            val labelName = label?.let { Languages.find(it)?.englishName }
            val packSource = label?.let { LabelReading.offlineSource(it, Languages.all.map { lang -> lang.tag }.toSet()) }
            when {
                label == null || labelName == null || Languages.base(label) == settings.language -> Unit
                packSource == null -> Text(
                    "$labelName has no offline pack, so it's translated online.",
                    style = Parity.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                downloadingPack == label -> SettingRow("$labelName for offline use", "Downloading… (about 30 MB)") {}
                packSource in offlinePacks -> SettingRow("$labelName for offline use", "Downloaded · tap to remove (it's then translated online)") {
                    controller.removeOfflinePack(packSource)
                }
                else -> SettingRow("Download $labelName for offline use", "About 30 MB · then names translate without a connection") {
                    controller.downloadOfflinePack(label)
                }
            }
            offlinePacks.filter { it != settings.language && it != "en" && it != packSource }.sorted().forEach { pack ->
                val name = Languages.find(pack)?.englishName ?: pack
                SettingRow("Remove $name offline pack", "Frees about 30 MB; $name is then translated online") { controller.removeOfflinePack(pack) }
            }
        }

        SectionHeader("AI reading")
        Group {
            Text(
                "AI is on every price, whether or not the phone's reading looks finished. Nothing is sent until you tap it. The answer replaces that reading. Tap the check if it's right — that's the only time this phone remembers the correction.",
                style = Parity.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            AiPreset.entries.forEach { preset ->
                val selected = preset == aiPreset
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { controller.setAiPreset(preset.name) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null, tint = if (selected) c.accent else c.textSecondary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(preset.label, style = Parity.type.body)
                        Text(
                            when (preset) {
                                AiPreset.SPACEXAI -> "Grok, at api.x.ai. Model grok-4.7."
                                AiPreset.OPENAI -> "ChatGPT, at api.openai.com."
                                AiPreset.CUSTOM -> "Any service that speaks the OpenAI chat format."
                            },
                            style = Parity.type.caption, color = c.textSecondary,
                        )
                    }
                }
            }
            if (aiPreset == AiPreset.CUSTOM) {
                OutlinedTextField(
                    value = aiUrl,
                    onValueChange = { aiUrl = it },
                    label = { Text("Address") },
                    placeholder = { Text("https://example.com/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = parityTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (aiPreset == AiPreset.OPENAI || aiPreset == AiPreset.CUSTOM) {
                OutlinedTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it },
                    label = { Text("Model name") },
                    placeholder = { Text(if (aiPreset == AiPreset.OPENAI) "gpt-4o" else "model name") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = parityTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = aiKey,
                onValueChange = { aiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(18.dp),
                colors = parityTextFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Save", { controller.saveAi(aiKey, aiUrl, aiModel) }, primary = false)
                PillButton("Test", { controller.saveAi(aiKey, aiUrl, aiModel, test = true) }, primary = false)
            }
            Text(
                "A confirmed name is remembered on this phone and used on later scans. Tesseract is given those words. ML Kit has no word list, so a line it keeps misreading is replaced after the same correction is confirmed twice. Neither model is retrained. The key stays on this phone and is left out of backups.",
                style = Parity.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        SectionHeader("Exchange rates")
        Group {
            RateProviderId.entries.forEach { provider ->
                val selected = provider == settings.rateProvider
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { controller.setProvider(provider) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null, tint = if (selected) c.accent else c.textSecondary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(provider.label, style = Parity.type.body)
                        Text("Updates: ${provider.resolution} · ${provider.note}", style = Parity.type.caption, color = c.textSecondary)
                    }
                }
            }
            if (settings.rateProvider.resolution.startsWith("Daily")) {
                Text(
                    "Daily rates mean scans on the same day show “=”. Pick CoinGecko or an hourly provider for finer tracking.",
                    style = Parity.type.caption, color = c.sale, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (settings.rateProvider.needsKey || settings.rateProvider == RateProviderId.COINGECKO) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (settings.rateProvider.needsKey) "API key" else "API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(18.dp),
                    colors = parityTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                PillButton("Save key", { controller.setApiKey(apiKey); controller.refreshRate() }, Modifier.padding(12.dp), primary = false)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val r = rate?.rate
                    if (settings.baseCurrency == settings.localCurrency) {
                        Text("Your currency and the local currency are the same.", style = Parity.type.caption, color = c.textSecondary)
                    } else if (r != null) {
                        Text("1 ${r.base.code} = ${formatRate(r.value, 10)} ${r.quote.code}", style = Parity.type.priceSmall)
                        Text(
                            (r.publishedAtMs?.let { "Published ${formatDateTime(it)} · " } ?: "") + "fetched ${formatAge(r.fetchedAtMs, now())}" +
                                (r.pivot?.let { " · via ${it.code}" } ?: "") + if (rate?.stale == true) " · offline" else "",
                            style = Parity.type.caption, color = c.textSecondary,
                        )
                    } else {
                        Text(rate?.error ?: "Checking the rate…", style = Parity.type.caption, color = if (rate?.error != null) c.down else c.textSecondary)
                    }
                }
                PillButton("Refresh", controller::refreshRate, primary = false)
            }
        }

        SectionHeader("Display")
        Group {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("True black", style = Parity.type.body)
                    Text("Pure black background for OLED screens", style = Parity.type.caption, color = c.textSecondary)
                }
                Switch(
                    checked = settings.trueBlack,
                    onCheckedChange = controller::setTrueBlack,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                )
            }
        }

        SectionHeader("Data")
        Group {
            SettingRow("Export all data", "ZIP of CSV files, saved where you choose") { controller.exportZip() }
            SettingRow("Restore from backup", "Replace everything or merge a backup ZIP") { controller.pickRestoreFile() }
            SettingRow("Send all data by QR", "Encrypted; the other phone types the code shown with it") { graph.transfer.sendBackup() }
            SettingRow("Receive by QR", "A list or all data, from another phone") { graph.transfer.startReceiving() }
        }

        SectionHeader("About")
        Group {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Parity ${graph.appVersion}", style = Parity.type.body)
                Text("Source Sans 3 (SIL Open Font License). Tesseract language data (Apache 2.0).", style = Parity.type.caption, color = c.textSecondary)
                Text(
                    "The network is used for exchange rates, text readers, translation packs, and product names when translating online. Tapping AI on a price sends that label to the service you set up above. Nothing is sent until you tap it.",
                    style = Parity.type.caption, color = c.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    when (picker) {
        Picker.BASE -> PickerDialog("Your currency", { picker = null }) {
            CurrencyList(settings.baseCurrency, { controller.setBaseCurrency(it); picker = null })
        }
        Picker.LANGUAGE -> PickerDialog("Your language", { picker = null }) {
            LanguageList(settings.language, { controller.setLanguage(it); picker = null })
        }
        Picker.COUNTRY -> PickerDialog("Country", { picker = null }) {
            CountryList(settings.country, { controller.setManualCountry(it); picker = null })
        }
        Picker.LOCAL -> PickerDialog("Local currency", { picker = null }) {
            CurrencyList(settings.localCurrency, { controller.setLocalCurrencyOverride(it); picker = null })
        }
        null -> Unit
    }

    pendingRestore?.let { contents ->
        AlertDialog(
            onDismissRequest = controller::cancelRestore,
            containerColor = c.surfaceRaised,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Restore backup?", style = Parity.type.headline) },
            text = {
                Column {
                    Text(contents.summary, style = Parity.type.body)
                    Spacer(Modifier.height(8.dp))
                    Text("Merge keeps your current data and adds anything new. Replace all deletes everything first.", style = Parity.type.caption, color = c.textSecondary)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Replace all", { controller.restore(RestoreMode.REPLACE_ALL) }, primary = false, destructive = true)
                    PillButton("Merge", { controller.restore(RestoreMode.MERGE) }, height = 44.dp)
                }
            },
            dismissButton = { PillButton("Cancel", controller::cancelRestore, primary = false) },
        )
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Parity.colors.surface).padding(4.dp)) { content() }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Parity.type.body)
            Text(value, style = Parity.type.caption, color = Parity.colors.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Parity.colors.textSecondary)
    }
}
