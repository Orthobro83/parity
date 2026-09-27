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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parity.core.fx.formatRate
import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyFormat
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.shared.app.AppGraph
import app.parity.shared.data.SessionDetail
import app.parity.shared.data.Settings
import app.parity.shared.data.ShoppingRepository
import app.parity.shared.ui.components.Chip
import app.parity.shared.ui.components.RoundIconButton
import app.parity.shared.ui.formatDateTime
import app.parity.shared.ui.theme.Parity

/** Past sessions with year / month / day search (design §11.2). */
@Composable
fun HistoryScreen(graph: AppGraph, settings: Settings) {
    val history = graph.history
    val filter by history.filter.collectAsState()
    val sessions by history.sessions.collectAsState()
    val c = Parity.colors

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("History", style = Parity.type.headline)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField("Year", filter.year, 4, Modifier.weight(1.4f)) { history.filter.value = filter.copy(year = it) }
            DateField("Month", filter.month, 2, Modifier.weight(1f)) { history.filter.value = filter.copy(month = it) }
            DateField("Day", filter.day, 2, Modifier.weight(1f)) { history.filter.value = filter.copy(day = it) }
            if (!filter.isEmpty) RoundIconButton(Icons.Rounded.Close, "Clear search", { history.filter.value = filter.copy(year = "", month = "", day = "") })
        }
        if (filter.year.isBlank() && (filter.month.isNotBlank() || filter.day.isNotBlank())) {
            Text("Add a year to search by month or day.", style = Parity.type.caption, color = c.sale, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (sessions.isEmpty()) {
            Text(
                if (filter.isEmpty) "Finalized shopping trips appear here." else "No shopping on that date.",
                style = Parity.type.body, color = c.textSecondary, modifier = Modifier.padding(top = 32.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { session ->
                val base = CurrencyCode(session.baseCurrency)
                val locals = ShoppingRepository.localTotals(session.totalsLocalJson).filterKeys { it != base }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .clickable { history.open(session.id) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatDateTime(session.finalizedAt), style = Parity.type.title)
                        Text("${session.itemCount} ${if (session.itemCount == 1) "item" else "items"}", style = Parity.type.caption, color = c.textSecondary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(MoneyFormat.format(decimal(session.totalBase), base), style = Parity.type.price)
                        locals.forEach { (code, total) ->
                            Text("(${MoneyFormat.format(total, code)})", style = Parity.type.priceSmall, color = c.textSecondary)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DateField(label: String, value: String, maxLength: Int, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(18.dp),
        colors = parityTextFieldColors(),
        modifier = modifier,
    )
}

/** One session: what was bought, in the user's currency and the local one, in the user's language. */
@Composable
fun SessionDetailScreen(detail: SessionDetail, onClose: () -> Unit) {
    val c = Parity.colors
    val base = CurrencyCode(detail.session.baseCurrency)
    Surface(color = c.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(detail.session.finalizedAt), style = Parity.type.headline)
                    detail.storeName?.let { Text(it, style = Parity.type.body, color = c.textSecondary) }
                }
                RoundIconButton(Icons.Rounded.Close, "Close", onClose)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text("Total ", style = Parity.type.body, color = c.textSecondary)
                Text(MoneyFormat.format(decimal(detail.session.totalBase), base), style = Parity.type.price)
                ShoppingRepository.localTotals(detail.session.totalsLocalJson).filterKeys { it != base }.forEach { (code, total) ->
                    Text("  (${MoneyFormat.format(total, code)})", style = Parity.type.priceSmall, color = c.textSecondary, modifier = Modifier.align(Alignment.Bottom))
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detail.lines, key = { it.id }) { line ->
                    val local = CurrencyCode(line.localCurrency)
                    val qty = decimal(line.quantity)
                    val unitLocal = decimal(line.unitPriceLocal)
                    val unitBase = line.unitPriceBase?.let(::decimal)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.nameTranslatedAtPurchase ?: line.nameAtPurchase ?: "Unnamed item", style = Parity.type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val original = line.nameAtPurchase
                                if (original != null && original != line.nameTranslatedAtPurchase) {
                                    Text(original, style = Parity.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (line.isPromo) Chip("SALE", color = c.sale, selected = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${MoneyFormat.quantity(qty)} × " + (unitBase?.let { MoneyFormat.format(it, base) + " " } ?: "") + "(${MoneyFormat.format(unitLocal, local)})",
                            style = Parity.type.priceSmall, color = c.textSecondary,
                        )
                        Row {
                            Text(
                                (unitBase?.let { MoneyFormat.format(it.multiply(qty, MoneyMath), base) + "  " } ?: "") +
                                    "(${MoneyFormat.format(unitLocal.multiply(qty, MoneyMath), local)})",
                                style = Parity.type.priceSmall, modifier = Modifier.weight(1f),
                            )
                            line.fxRate?.let { Text("1 ${base.code} = ${formatRate(decimal(it))} ${local.code}", style = Parity.type.caption, color = c.textSecondary) }
                        }
                    }
                }
                item { Spacer(Modifier.width(1.dp).height(24.dp)) }
            }
        }
    }
}
