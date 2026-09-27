package app.parity.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parity.core.fx.formatPercent
import app.parity.core.fx.formatPercentPrecise
import app.parity.core.fx.formatRate
import app.parity.core.money.MoneyFormat
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.core.money.divideMoney
import app.parity.core.money.toPlain
import app.parity.shared.app.AppGraph
import app.parity.shared.app.ScanCard
import app.parity.shared.data.CartItem
import app.parity.shared.data.Settings
import app.parity.shared.ui.components.Chip
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.components.RoundIconButton
import app.parity.shared.ui.formatDate
import app.parity.shared.ui.parseUserAmount
import app.parity.shared.ui.theme.Parity
import com.ionspin.kotlin.bignum.decimal.BigDecimal

@Composable
fun parityTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Parity.colors.accent,
    unfocusedBorderColor = Parity.colors.outline,
    focusedContainerColor = Parity.colors.surfaceRaised,
    unfocusedContainerColor = Parity.colors.surfaceRaised,
    cursorColor = Parity.colors.accent,
    focusedLabelColor = Parity.colors.accent,
    unfocusedLabelColor = Parity.colors.textSecondary,
)

@Composable
private fun ParitySheet(onDismiss: () -> Unit, skipPartial: Boolean = true, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartial),
        containerColor = Parity.colors.surface,
        contentColor = Parity.colors.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Parity.colors.outline) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            content()
        }
    }
}

/** "How many?" after Buy (design §7). Decimals are allowed for weighed goods. */
@Composable
fun QuantitySheet(card: ScanCard, onAdd: (BigDecimal) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("1") }
    val quantity = parseUserAmount(text)
    ParitySheet(onDismiss) {
        Text("How many?", style = Parity.type.headline)
        card.name?.let { Text(it, style = Parity.type.body, color = Parity.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton(Icons.Rounded.Remove, "Less", {
                val q = parseUserAmount(text) ?: BigDecimal.ONE
                val next = q - BigDecimal.ONE
                text = if (next.signum() > 0) next.toPlain() else text
            }, size = 52.dp)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(8) },
                singleLine = true,
                textStyle = Parity.type.price.copy(textAlign = TextAlign.Center, color = Parity.colors.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(26.dp),
                colors = parityTextFieldColors(),
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(Icons.Rounded.Add, "More", {
                val q = parseUserAmount(text) ?: BigDecimal.ZERO
                text = (q + BigDecimal.ONE).toPlain()
            }, size = 52.dp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1", "2", "3", "5", "10").forEach { preset -> Chip(preset, selected = text == preset, onClick = { text = preset }) }
        }
        quantity?.let { q ->
            card.basePrice?.let { base ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "${MoneyFormat.format(base.multiply(q, MoneyMath), card.baseCurrency)}  (${MoneyFormat.format(card.localPrice.multiply(q, MoneyMath), card.localCurrency)})",
                    style = Parity.type.priceSmall, color = Parity.colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Cancel", onDismiss, Modifier.weight(1f), primary = false, height = 52.dp)
            PillButton("Add to cart", { quantity?.let(onAdd) }, Modifier.weight(1f), enabled = quantity != null)
        }
    }
}

/** The expanded running total: every cart line, adjustable, swipe right to remove (design §7). */
@Composable
fun CartSheet(graph: AppGraph, settings: Settings, onDismiss: () -> Unit) {
    val items by graph.home.cart.collectAsState()
    val summary by graph.home.summary.collectAsState()
    ParitySheet(onDismiss, skipPartial = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cart", style = Parity.type.headline, modifier = Modifier.weight(1f))
            summary?.let { s ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(MoneyFormat.format(s.totalBase, s.baseCurrency), style = Parity.type.price)
                    s.localTotals.filterKeys { it != s.baseCurrency }.forEach { (code, total) ->
                        Text("(${MoneyFormat.format(total, code)})", style = Parity.type.priceSmall, color = Parity.colors.textSecondary)
                    }
                }
            }
        }
        if (summary?.missingRates == true) {
            Text("Some items were scanned without an exchange rate and aren't in the converted total.", style = Parity.type.caption, color = Parity.colors.sale)
        }
        Text("Swipe an item right to remove it.", style = Parity.type.caption, color = Parity.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text("Your cart is empty.", style = Parity.type.body, color = Parity.colors.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.line.id }) { item ->
                CartRow(item, settings, onQuantity = { graph.home.setQuantity(item, it) }, onRemove = { graph.home.remove(item) })
            }
        }
    }
}

@Composable
private fun CartRow(item: CartItem, settings: Settings, onQuantity: (BigDecimal) -> Unit, onRemove: () -> Unit) {
    val c = Parity.colors
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.StartToEnd) onRemove() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(c.down.copy(alpha = 0.22f), RoundedCornerShape(20.dp)).padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = c.down) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.product.displayName ?: "Unnamed item", style = Parity.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val original = item.product.originalName
                if (original != null && original != item.product.displayName) {
                    Text(original, style = Parity.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${MoneyFormat.quantity(item.quantity)} × ${MoneyFormat.format(item.unitLocal, item.localCurrency)}" +
                        if (item.observation.isPromo) "  · SALE" else "",
                    style = Parity.type.caption, color = c.textSecondary,
                )
                val lineBase = item.lineBase
                Text(
                    (lineBase?.let { MoneyFormat.format(it, settings.baseCurrency) + "  " } ?: "") +
                        "(${MoneyFormat.format(item.lineLocal, item.localCurrency)})",
                    style = Parity.type.priceSmall,
                )
            }
            RoundIconButton(Icons.Rounded.Remove, "One less", { onQuantity(item.quantity - BigDecimal.ONE) }, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Rounded.Add, "One more", { onQuantity(item.quantity + BigDecimal.ONE) }, size = 36.dp)
        }
    }
}

/** Price entry without the camera. */
@Composable
fun ManualEntrySheet(settings: Settings, onShow: (BigDecimal, String?) -> Unit, onDismiss: () -> Unit) {
    var price by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val amount = parseUserAmount(price)
    ParitySheet(onDismiss) {
        Text("Type a price", style = Parity.type.headline)
        Text("In ${settings.localCurrency.code}", style = Parity.type.body, color = Parity.colors.textSecondary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = price, onValueChange = { price = it.take(12) }, label = { Text("Price") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(18.dp),
            colors = parityTextFieldColors(), textStyle = Parity.type.price, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(80) }, label = { Text("Product name (optional)") }, singleLine = true,
            shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        PillButton("Show price", { amount?.let { onShow(it, name) } }, Modifier.fillMaxWidth(), enabled = amount != null)
    }
}

/** "Are you sure?" when list items are still unpurchased (design §11.1). */
@Composable
fun FinalizeDialog(open: List<String>, onKeepShopping: () -> Unit, onFinalize: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepShopping,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Are you sure?", style = Parity.type.headline) },
        text = {
            Column {
                Text("You still haven't purchased these items from the shopping list:", style = Parity.type.body)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(open) { Text("•  $it", style = Parity.type.body, color = Parity.colors.textPrimary) }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Keep Shopping", onKeepShopping, Modifier.weight(1f), primary = false, height = 48.dp)
                PillButton("Finalize purchases", onFinalize, Modifier.weight(1f), height = 48.dp)
            }
        },
    )
}

@Composable
fun EditNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Product name", style = Parity.type.headline) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(80) }, singleLine = true,
                shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { PillButton("Save", { onSave(text) }, height = 44.dp, enabled = text.isNotBlank()) },
        dismissButton = { PillButton("Cancel", onDismiss, primary = false) },
    )
}

/** The popover behind ▲/▼: rates then and now, and the shelf-price change kept separate (design §9). */
@Composable
fun FxDetailDialog(card: ScanCard, onDismiss: () -> Unit) {
    val indicator = card.indicator ?: return
    val previous = card.previous?.observation
    val base = card.baseCurrency.code
    val local = card.localCurrency.code
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Your ${card.baseCurrency.code} vs ${card.localCurrency.code}", style = Parity.type.headline) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (previous != null) {
                    Text(
                        "Last scanned ${formatDate(previous.observedAt)}" + (card.previous.storeName?.let { " at $it" } ?: "") + ".",
                        style = Parity.type.body,
                    )
                }
                Text("Then: 1 $base = ${formatRate(indicator.previousRate, 10)} $local", style = Parity.type.priceSmall)
                Text("Now: 1 $base = ${formatRate(indicator.currentRate, 10)} $local", style = Parity.type.priceSmall)
                Text("Change: ${formatPercentPrecise(indicator.change)}", style = Parity.type.priceSmall)
                if (previous != null) {
                    val oldShelf = decimal(previous.price)
                    val shelfChange = card.localPrice.divideMoney(oldShelf) - BigDecimal.ONE
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Shelf price: ${MoneyFormat.format(oldShelf, card.localCurrency)} → ${MoneyFormat.format(card.localPrice, card.localCurrency)} (${formatPercent(shelfChange)})",
                        style = Parity.type.priceSmall,
                    )
                    val oldBase = oldShelf.divideMoney(indicator.previousRate)
                    val newBase = card.localPrice.divideMoney(indicator.currentRate)
                    Text(
                        "In $base: ${MoneyFormat.format(oldBase, card.baseCurrency)} → ${MoneyFormat.format(newBase, card.baseCurrency)} (${formatPercent(newBase.divideMoney(oldBase) - BigDecimal.ONE)})",
                        style = Parity.type.priceSmall,
                    )
                }
            }
        },
        confirmButton = { PillButton("Done", onDismiss, height = 44.dp) },
    )
}
