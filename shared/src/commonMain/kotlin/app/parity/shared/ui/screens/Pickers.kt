package app.parity.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.parity.core.money.Countries
import app.parity.core.money.Currencies
import app.parity.core.money.Currency
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Language
import app.parity.core.money.Languages
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.theme.Parity

/** Searchable list used by every picker. */
@Composable
fun <T> SearchableList(
    items: List<T>,
    selected: T?,
    matches: (T, String) -> Boolean,
    title: (T) -> String,
    subtitle: (T) -> String?,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search") },
            singleLine = true,
            shape = CircleShape,
            colors = parityTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val filtered = items.filter { query.isBlank() || matches(it, query.trim().lowercase()) }
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(filtered) { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(item) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title(item), style = Parity.type.body, color = if (item == selected) Parity.colors.accent else Parity.colors.textPrimary)
                        subtitle(item)?.let { Text(it, style = Parity.type.caption, color = Parity.colors.textSecondary) }
                    }
                    if (item == selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = Parity.colors.accent)
                }
            }
        }
    }
}

private val popularCurrencies = listOf("USD", "EUR", "GBP", "CAD", "AUD", "GEL", "CHF", "JPY", "BTC", "ZEC", "ETH", "XMR")

private val popularLanguages = listOf("en", "ka", "ru", "es", "fr", "de", "it", "pt", "tr", "uk")

/** The selected item first, then common choices, then everything else in order. */
private fun <T> pinned(all: List<T>, selected: T?, popular: List<T>): List<T> =
    (listOfNotNull(selected) + popular + all).distinct()

@Composable
fun CurrencyList(selected: CurrencyCode?, onPick: (CurrencyCode) -> Unit, modifier: Modifier = Modifier) {
    val all = remember(selected) {
        val sorted = Currencies.all.sortedWith(compareBy<Currency>({ it.isCrypto }, { it.code.code }))
        pinned(sorted, sorted.firstOrNull { it.code == selected }, popularCurrencies.mapNotNull { Currencies.find(it) })
    }
    SearchableList(
        items = all,
        selected = all.firstOrNull { it.code == selected },
        matches = { c, q -> c.code.code.lowercase().contains(q) || c.name.lowercase().contains(q) },
        title = { "${it.code.code} · ${it.name}" },
        subtitle = { if (it.isCrypto) "Crypto · ${it.symbol}" else it.symbol },
        onPick = { onPick(it.code) },
        modifier = modifier,
    )
}

@Composable
fun LanguageList(selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val all = remember(selected) {
        pinned(Languages.all, Languages.all.firstOrNull { it.tag == selected }, popularLanguages.mapNotNull { Languages.find(it) })
    }
    SearchableList(
        items = all,
        selected = Languages.all.firstOrNull { it.tag == selected },
        matches = { l: Language, q -> l.englishName.lowercase().contains(q) || l.nativeName.lowercase().contains(q) || l.tag == q },
        title = { it.englishName },
        subtitle = { it.nativeName.takeIf { native -> native != it.englishName } },
        onPick = { onPick(it.tag) },
        modifier = modifier,
    )
}

@Composable
fun CountryList(selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val codes = remember { Countries.allCodes.sortedBy { Countries.name(it) } }
    SearchableList(
        items = codes,
        selected = selected,
        matches = { code, q -> code.lowercase() == q || Countries.name(code).lowercase().contains(q) },
        title = { "${Countries.flag(it)}  ${Countries.name(it)}" },
        subtitle = { Countries.currencyFor(it)?.code },
        onPick = onPick,
        modifier = modifier,
    )
}

@Composable
fun PickerDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = Parity.type.headline) },
        text = { content() },
        confirmButton = {},
        dismissButton = { PillButton("Close", onDismiss, primary = false) },
    )
}
