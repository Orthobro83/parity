package app.parity.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parity.core.fx.formatPercent
import app.parity.core.fx.formatPercentPrecise
import app.parity.core.fx.formatRate
import app.parity.core.fx.formatRatePlain
import app.parity.core.money.MoneyFormat
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.core.money.divideMoney
import app.parity.core.money.toFixed
import app.parity.core.money.toPlain
import app.parity.core.scan.MultiBuyOffer
import app.parity.shared.app.AppGraph
import app.parity.shared.app.ScanCard
import app.parity.shared.data.CartItem
import app.parity.shared.data.Settings
import app.parity.shared.ui.components.Chip
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.components.RoundIconButton
import app.parity.shared.ui.formatDate
import app.parity.shared.ui.parseUserAmount
import app.parity.shared.ui.plural
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

/**
 * "How many?" after Buy (design §7). When the tag has a multi-buy deal, the shopper first chooses
 * between one at the regular price and the deal quantity. Decimals are allowed for weighed goods.
 */
@Composable
fun QuantitySheet(card: ScanCard, onAdd: (BigDecimal, Boolean) -> Unit, onDismiss: () -> Unit) {
    val deal = card.multiBuy
    var custom by remember { mutableStateOf(deal == null) }
    ParitySheet(onDismiss) {
        if (deal != null && !custom) {
            DealChoices(card, deal, onAdd = onAdd, onOther = { custom = true })
        } else {
            QuantityStepper(card, deal, onAdd, onDismiss)
        }
    }
}

@Composable
private fun DealChoices(card: ScanCard, deal: MultiBuyOffer, onAdd: (BigDecimal, Boolean) -> Unit, onOther: () -> Unit) {
    val c = Parity.colors
    val regular = card.localPrice
    val dealTotal = deal.total(deal.quantity, regular)
    val saving = regular.multiply(BigDecimal.fromInt(deal.quantity), MoneyMath) - dealTotal
    Text("This tag has a deal", style = Parity.type.headline)
    Text(deal.describe(card.localCurrency), style = Parity.type.body, color = c.accent)
    card.name?.let { Text(it, style = Parity.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    Spacer(Modifier.height(16.dp))
    // A deal-only tag has no single price to offer.
    if (deal.singlePriceShown) {
        ChoiceCard(
            title = "Just 1 at the regular price",
            amount = pricePair(card, regular),
            detail = null,
            highlighted = false,
            onClick = { onAdd(BigDecimal.ONE, false) },
        )
        Spacer(Modifier.height(10.dp))
    }
    ChoiceCard(
        title = "${deal.quantity} with the deal",
        amount = pricePair(card, dealTotal),
        // On a deal-only tag the card's price already is the deal's price per item.
        detail = if (deal.singlePriceShown) {
            "${pricePair(card, deal.dealUnitPrice(regular))} each · save ${MoneyFormat.format(saving, card.localCurrency)}"
        } else {
            "${pricePair(card, regular)} each"
        },
        highlighted = true,
        onClick = { onAdd(BigDecimal.fromInt(deal.quantity), true) },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Another quantity…",
        style = Parity.type.label,
        color = c.textSecondary,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onOther).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** "$9.19 (₾23.97)", or just the local amount when there's no rate or both currencies match. */
private fun pricePair(card: ScanCard, local: BigDecimal): String {
    val base = card.rate?.localToBase(local)
    return if (base == null || card.baseCurrency == card.localCurrency) MoneyFormat.format(local, card.localCurrency)
    else "${MoneyFormat.format(base, card.baseCurrency)} (${MoneyFormat.format(local, card.localCurrency)})"
}

@Composable
private fun ChoiceCard(title: String, amount: String, detail: String?, highlighted: Boolean, onClick: () -> Unit) {
    val c = Parity.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(if (highlighted) c.accent.copy(alpha = 0.14f) else c.surfaceRaised)
            .border(1.dp, if (highlighted) c.accent else c.outline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(title, style = Parity.type.title, color = c.textPrimary)
        Text(amount, style = Parity.type.price, color = if (highlighted) c.accent else c.textPrimary)
        detail?.let { Text(it, style = Parity.type.caption, color = c.textSecondary) }
    }
}

@Composable
private fun QuantityStepper(card: ScanCard, deal: MultiBuyOffer?, onAdd: (BigDecimal, Boolean) -> Unit, onDismiss: () -> Unit) {
    // On a deal-only tag the deal is the only price there is.
    val dealOnly = deal != null && !deal.singlePriceShown
    var useDeal by remember { mutableStateOf(dealOnly) }
    var text by remember { mutableStateOf(if (dealOnly) deal!!.quantity.toString() else "1") }
    val quantity = parseUserAmount(text)
    val step = if (useDeal && deal != null) deal.step else 1
    Text("How many?", style = Parity.type.headline)
    card.name?.let { Text(it, style = Parity.type.body, color = Parity.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundIconButton(Icons.Rounded.Remove, "Less", {
            val q = parseUserAmount(text) ?: BigDecimal.ONE
            val next = q - BigDecimal.fromInt(step)
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
            text = (q + BigDecimal.fromInt(step)).toPlain()
        }, size = 52.dp)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("1", "2", "3", "5", "10").forEach { preset -> Chip(preset, selected = text == preset, onClick = { text = preset }) }
    }
    if (deal != null) {
        Spacer(Modifier.height(12.dp))
        Chip(
            "Use the deal: ${deal.describe(card.localCurrency)}",
            color = Parity.colors.accent,
            selected = useDeal,
            onClick = {
                useDeal = !useDeal
                if (useDeal && (quantity == null || quantity < BigDecimal.fromInt(deal.quantity))) text = deal.quantity.toString()
            },
        )
    }
    quantity?.let { q ->
        val units = q.toPlain().takeIf { t -> t.all { it.isDigit() } }?.toIntOrNull()
        val total = if (useDeal && deal != null && units != null) deal.total(units, card.localPrice) else card.localPrice.multiply(q, MoneyMath)
        Spacer(Modifier.height(12.dp))
        Text(pricePair(card, total), style = Parity.type.priceSmall, color = Parity.colors.textSecondary)
        if (useDeal && deal != null && (units == null || !deal.appliesTo(units))) {
            Text(
                if (dealOnly) "The deal needs ${deal.quantity}, and the tag shows no single price: this uses the deal's price per item."
                else "The deal needs ${deal.quantity}; this is the regular price.",
                style = Parity.type.caption, color = Parity.colors.sale,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Cancel", onDismiss, Modifier.weight(1f), primary = false, height = 52.dp)
        PillButton("Add to cart", { quantity?.let { onAdd(it, useDeal) } }, Modifier.weight(1f), enabled = quantity != null)
    }
}

/** Adds, corrects or removes a multi-buy deal the camera missed or misread. */
@Composable
fun DealDialog(card: ScanCard, onSave: (MultiBuyOffer?) -> Unit, onDismiss: () -> Unit) {
    val existing = card.multiBuy
    val dealOnly = existing?.singlePriceShown == false
    var units by remember { mutableStateOf(existing?.quantity?.toString() ?: "3") }
    var price by remember { mutableStateOf(existing?.dealUnitPrice(card.localPrice)?.toFixed(2) ?: "") }
    val n = units.toIntOrNull()?.takeIf { it in 2..20 }
    val amount = parseUserAmount(price)
    val offer = if (n != null && amount != null) MultiBuyOffer.resolve(n, amount, card.localPrice) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (existing == null) "Add a deal" else "Deal", style = Parity.type.headline) },
        text = {
            if (dealOnly) {
                // The price per item comes from the deal, so the deal can only be kept or removed.
                Column {
                    Text(existing!!.describe(card.localCurrency), style = Parity.type.body, color = Parity.colors.accent)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The tag shows only this deal, so the price per item comes from it. Remove it if the price is for one item.",
                        style = Parity.type.caption, color = Parity.colors.textSecondary,
                    )
                }
            } else {
                Column {
                    Text("Regular price: ${MoneyFormat.format(card.localPrice, card.localCurrency)}", style = Parity.type.body, color = Parity.colors.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = units, onValueChange = { units = it.filter(Char::isDigit).take(2) }, label = { Text("Buy") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors(), modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = price, onValueChange = { price = it.take(10) }, label = { Text("Deal price") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors(), modifier = Modifier.weight(1.4f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            offer != null -> offer.describe(card.localCurrency)
                            n != null && amount != null -> "That isn't cheaper than the regular price."
                            else -> "Enter the price per item, or the total for all of them."
                        },
                        style = Parity.type.caption,
                        color = if (offer != null) Parity.colors.accent else Parity.colors.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) PillButton("Remove", { onSave(null) }, primary = false, destructive = true)
                if (!dealOnly) PillButton("Save", { onSave(offer) }, height = 44.dp, enabled = offer != null)
            }
        },
        dismissButton = { PillButton("Cancel", onDismiss, primary = false) },
    )
}

/** The expanded running total: every cart line, adjustable, swipe right to remove (design §7). */
@Composable
fun CartSheet(graph: AppGraph, settings: Settings, onDismiss: () -> Unit) {
    val items by graph.home.cart.collectAsState()
    val summary by graph.home.summary.collectAsState()
    ParitySheet(onDismiss, skipPartial = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cart", style = Parity.type.headline, modifier = Modifier.weight(1f))
            summary?.let { s -> Text(plural(s.count, "item"), style = Parity.type.body, color = Parity.colors.textSecondary) }
        }
        summary?.let { s ->
            // Your currency first, the local total beside it, then the rate that links them (design §7).
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(MoneyFormat.format(s.totalBase, s.baseCurrency), style = Parity.type.price)
                val locals = s.localTotals.filterKeys { it != s.baseCurrency }
                if (locals.isNotEmpty()) {
                    Text(
                        locals.entries.joinToString("  ") { (code, total) -> "(${MoneyFormat.format(total, code)})" },
                        style = Parity.type.priceSmall, color = Parity.colors.textSecondary,
                        modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                    )
                }
            }
            s.rates.forEach { (code, rate) ->
                Text("1 ${s.baseCurrency.code} = ${formatRatePlain(rate)} ${code.code}", style = Parity.type.body, color = Parity.colors.textSecondary)
            }
            Spacer(Modifier.height(6.dp))
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
                item.offer?.let { deal ->
                    Text(
                        when {
                            !item.dealApplied && !deal.singlePriceShown -> "Deal needs ${deal.quantity}; priced at the deal's rate"
                            !item.dealApplied -> "Deal needs ${deal.quantity}; regular price for now"
                            !deal.singlePriceShown -> "Deal: ${deal.describe(item.localCurrency)}"
                            else -> "Deal: ${deal.describe(item.localCurrency)} · regular ${MoneyFormat.format(item.regularUnit, item.localCurrency)}"
                        },
                        style = Parity.type.caption, color = if (item.dealApplied) c.accent else c.sale,
                    )
                }
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

/** Price entry without the camera, with an optional multi-buy deal. */
@Composable
fun ManualEntrySheet(settings: Settings, onShow: (BigDecimal, String?, MultiBuyOffer?) -> Unit, onDismiss: () -> Unit) {
    var price by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var dealUnits by remember { mutableStateOf("") }
    var dealPrice by remember { mutableStateOf("") }
    val amount = parseUserAmount(price)
    val n = dealUnits.toIntOrNull()?.takeIf { it in 2..20 }
    val dealAmount = parseUserAmount(dealPrice)
    val deal = if (amount != null && n != null && dealAmount != null) MultiBuyOffer.resolve(n, dealAmount, amount) else null
    val dealTyped = dealUnits.isNotBlank() || dealPrice.isNotBlank()
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = dealUnits, onValueChange = { dealUnits = it.filter(Char::isDigit).take(2) }, label = { Text("Deal: buy") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors(), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = dealPrice, onValueChange = { dealPrice = it.take(10) }, label = { Text("Deal price") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors(), modifier = Modifier.weight(1.4f),
            )
        }
        if (dealTyped) {
            Text(
                deal?.describe(settings.localCurrency) ?: "Optional: e.g. buy 3 at 7.99 each, or 3 for 20",
                style = Parity.type.caption,
                color = if (deal != null) Parity.colors.accent else Parity.colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        PillButton("Show price", { amount?.let { onShow(it, name, deal) } }, Modifier.fillMaxWidth(), enabled = amount != null)
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
fun EditNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit, title: String = "Product name") {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = Parity.type.headline) },
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
