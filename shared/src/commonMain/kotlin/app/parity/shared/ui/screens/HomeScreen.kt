package app.parity.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Languages
import app.parity.core.money.MoneyFormat
import app.parity.core.scan.LabelReading
import app.parity.shared.app.AppGraph
import app.parity.shared.app.CartSummary
import app.parity.shared.app.NameStatus
import app.parity.shared.app.ScanCard
import app.parity.shared.app.TranslationStatus
import app.parity.shared.data.Settings
import app.parity.shared.data.ShoppingListItemEntity
import app.parity.shared.platform.ScanMode
import app.parity.shared.ui.components.Chip
import app.parity.shared.ui.components.FxBadge
import app.parity.shared.ui.components.GlassPanel
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.components.RollingText
import app.parity.shared.ui.components.RoundIconButton
import app.parity.shared.ui.components.strikeThrough
import app.parity.shared.ui.formatAge
import app.parity.shared.ui.plural
import app.parity.shared.ui.theme.Parity
import app.parity.shared.util.now
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home: camera, running total, result card, list strip and Finalize (design §4.1). */
@Composable
fun HomeScreen(graph: AppGraph, settings: Settings, cameraActive: Boolean) {
    val home = graph.home
    val card by home.card.collectAsState()
    val capturing by home.capturing.collectAsState()
    val summary by home.summary.collectAsState()
    val banner by graph.location.banner.collectAsState()
    val cameraGranted by graph.platform.permissions.camera.collectAsState()
    val listItems by graph.listController.items.collectAsState()
    val scope = rememberCoroutineScope()

    var showCart by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf<ScanCard?>(null) }
    var manualEntry by remember { mutableStateOf(false) }
    var finalizeItems by remember { mutableStateOf<List<String>?>(null) }
    var editingName by remember { mutableStateOf<ScanCard?>(null) }
    var fxDetail by remember { mutableStateOf<ScanCard?>(null) }
    var editingDeal by remember { mutableStateOf<ScanCard?>(null) }

    val overlay = showCart || buying != null || manualEntry || finalizeItems != null || editingName != null ||
        fxDetail != null || editingDeal != null
    LaunchedEffect(overlay) { home.paused.value = overlay }
    LaunchedEffect(cameraGranted) { if (!cameraGranted) graph.platform.permissions.requestCamera() }
    // Re-check the country every 5 minutes while scanning (design §5).
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60_000L)
            graph.location.refresh()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted && cameraActive) {
            graph.platform.camera.Preview(
                modifier = Modifier.fillMaxSize(),
                mode = ScanMode.PRICE_TAGS,
                active = !overlay,
                onFrame = home::onFrame,
                onQrCode = home::onQrCode,
            )
        } else if (!cameraGranted) {
            CameraPermissionPrompt(
                onAllow = { graph.platform.permissions.requestCamera() },
                onType = { manualEntry = true },
            )
        }
        // Soft scrims keep the overlays legible on bright shelves.
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Color(0xCC0B0D10), Color.Transparent))))
        Box(
            Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0D10)))),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            TotalBar(summary, settings, Modifier.fillMaxWidth()) { if (summary != null) showCart = true }
            // The cart on the left and camera controls on the right, so the totals keep the full width.
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                CartButton(summary?.count ?: 0, onClick = { showCart = true })
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIconButton(Icons.Rounded.PhotoLibrary, "Scan a photo", home::scanPhoto, background = Parity.colors.glass)
                    RoundIconButton(Icons.Rounded.Keyboard, "Type a price", { manualEntry = true }, background = Parity.colors.glass)
                }
            }
            AnimatedVisibility(banner != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                banner?.let { b ->
                    GlassPanel(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(graph.location.bannerText(b), style = Parity.type.body, color = Parity.colors.textPrimary, modifier = Modifier.weight(1f))
                            Chip("Undo", onClick = graph.location::undoBanner)
                            Spacer(Modifier.width(6.dp))
                            Chip("OK", onClick = graph.location::dismissBanner)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(card == null && cameraGranted, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (summary == null) {
                        GlassPanel(shape = CircleShape) {
                            Text(
                                "Point at a price tag",
                                style = Parity.type.label,
                                color = Parity.colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    ShutterButton(busy = capturing, onClick = home::capture)
                }
            }

            AnimatedVisibility(
                visible = card != null,
                enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
                    fadeIn(tween(160)) + scaleIn(initialScale = 0.92f),
                exit = slideOutVertically(tween(180)) { it / 3 } + fadeOut(tween(140)),
            ) {
                card?.let { c ->
                    ResultCard(
                        card = c,
                        onBuy = { buying = c },
                        onCancel = home::cancel,
                        onToggleSale = { home.setPromo(!c.isPromo) },
                        onAlternative = home::chooseAlternative,
                        onEditName = { editingName = c },
                        onFxDetail = { fxDetail = c },
                        onCurrency = home::chooseCurrency,
                        onDeal = { editingDeal = c },
                        labelLanguage = settings.labelLanguage,
                    )
                }
            }

            ListStrip(listItems, onToggle = graph.listController::toggle)

            AnimatedVisibility(summary != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                PillButton(
                    text = "Finalize (${summary?.count ?: 0})",
                    onClick = {
                        scope.launch {
                            val open = home.unpurchasedListItems()
                            if (open.isEmpty()) home.finalize() else finalizeItems = open
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    buying?.let { c ->
        QuantitySheet(
            card = c,
            onAdd = { qty, useDeal -> home.buy(qty, useDeal); buying = null },
            onDismiss = { buying = null },
        )
    }
    if (showCart) CartSheet(graph, settings, onDismiss = { showCart = false })
    if (manualEntry) {
        ManualEntrySheet(settings, onShow = { price, name, deal -> home.manualEntry(price, name, deal); manualEntry = false }, onDismiss = { manualEntry = false })
    }
    editingDeal?.let { c ->
        // The card may have changed (e.g. its observation was recorded) since the dialog opened.
        DealDialog(home.card.value ?: c, onSave = { home.setMultiBuy(it); editingDeal = null }, onDismiss = { editingDeal = null })
    }
    finalizeItems?.let { open ->
        FinalizeDialog(open, onKeepShopping = { finalizeItems = null }, onFinalize = { home.finalize(); finalizeItems = null })
    }
    editingName?.let { c ->
        EditNameDialog(c.name ?: c.originalName ?: "", onSave = { home.rename(it); editingName = null }, onDismiss = { editingName = null })
    }
    fxDetail?.let { c -> FxDetailDialog(c, onDismiss = { fxDetail = null }) }
}

/** The shutter: a photo of the tag when live scanning doesn't pick it up (design §6). */
@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = "Take a photo of the price tag" }
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(if (busy) Color.White.copy(alpha = 0.45f) else Color.White))
    }
}

/** The cart, with a badge for the number of items in it; opens the cart list (design §7). */
@Composable
private fun CartButton(count: Int, onClick: () -> Unit) {
    val c = Parity.colors
    Box {
        RoundIconButton(
            Icons.Rounded.ShoppingCart,
            if (count == 0) "Cart, empty" else "Cart, ${plural(count, "item")}",
            onClick,
            background = c.glass,
        )
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .clip(CircleShape).background(c.accent).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (count > 99) "99+" else "$count", style = Parity.type.caption, color = c.onAccent)
            }
        }
    }
}

@Composable
private fun TotalBar(summary: CartSummary?, settings: Settings, modifier: Modifier, onClick: () -> Unit) {
    val c = Parity.colors
    GlassPanel(modifier.clip(CircleShape).clickable(onClick = onClick), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (summary == null) {
                Text(settings.baseCurrency.code, style = Parity.type.label, color = c.textPrimary)
                Text("  ⇄  ", style = Parity.type.label, color = c.textSecondary)
                Text(settings.localCurrency.code, style = Parity.type.label, color = c.textPrimary)
                Spacer(Modifier.weight(1f))
            } else {
                RollingText(MoneyFormat.format(summary.totalBase, summary.baseCurrency), Parity.type.price, c.textPrimary)
                Spacer(Modifier.width(8.dp))
                val local = summary.localTotals[settings.localCurrency] ?: summary.localTotals.values.firstOrNull()
                val localCode = if (summary.localTotals.containsKey(settings.localCurrency)) settings.localCurrency else summary.localTotals.keys.firstOrNull()
                if (local != null && localCode != null && localCode != summary.baseCurrency) {
                    Text(
                        "(${MoneyFormat.format(local, localCode)})",
                        style = Parity.type.priceSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // The item count is on the cart button below.
                Icon(Icons.Rounded.ExpandMore, contentDescription = "Open cart, ${plural(summary.count, "item")}", tint = c.textSecondary)
            }
        }
    }
}

@Composable
private fun ResultCard(
    card: ScanCard,
    onBuy: () -> Unit,
    onCancel: () -> Unit,
    onToggleSale: () -> Unit,
    onAlternative: (BigDecimal) -> Unit,
    onEditName: () -> Unit,
    onFxDetail: () -> Unit,
    onCurrency: (CurrencyCode) -> Unit,
    onDeal: () -> Unit,
    labelLanguage: String?,
) {
    val c = Parity.colors
    GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    val base = card.basePrice
                    if (base != null && card.baseCurrency != card.localCurrency) {
                        CountUpMoney(card.id, base, card.baseCurrency)
                        // A deal-only tag's price is the deal's price per item.
                        val each = if (card.multiBuy?.singlePriceShown == false) " each" else ""
                        Text("(${MoneyFormat.format(card.localPrice, card.localCurrency)}$each)", style = Parity.type.priceSmall, color = c.textSecondary)
                    } else {
                        Text(MoneyFormat.format(card.localPrice, card.localCurrency), style = Parity.type.display, color = c.textPrimary)
                        if (card.baseCurrency != card.localCurrency) {
                            Text(
                                if (card.rateLoading) "Getting the exchange rate…" else "No exchange rate available offline yet",
                                style = Parity.type.caption, color = c.textSecondary,
                            )
                        }
                    }
                }
                card.indicator?.let { FxBadge(it, onClick = onFxDetail) }
            }
            card.multiBuy?.let { deal ->
                val text = if (deal.singlePriceShown) {
                    val each = deal.dealUnitPrice(card.localPrice)
                    val eachBase = card.rate?.localToBase(each)?.takeIf { card.baseCurrency != card.localCurrency }
                    "Deal: ${deal.describe(card.localCurrency)}" + (eachBase?.let { " → ${MoneyFormat.format(it, card.baseCurrency)} each" } ?: "")
                } else {
                    val total = deal.total(deal.quantity, card.localPrice)
                    val totalBase = card.rate?.localToBase(total)?.takeIf { card.baseCurrency != card.localCurrency }
                    "Only the deal is on the tag: ${deal.describe(card.localCurrency)}" + (totalBase?.let { " → ${MoneyFormat.format(it, card.baseCurrency)}" } ?: "")
                }
                Text(text, style = Parity.type.label, color = c.accent, modifier = Modifier.padding(top = 4.dp))
            }
            if (card.rateStale && card.rate != null) {
                Text("Rate from ${formatAge(card.rate.fetchedAtMs, now())} (offline)", style = Parity.type.caption, color = c.sale)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.clickable(onClick = onEditName), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val name = card.name
                    when {
                        name != null -> Text(name, style = Parity.type.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        card.nameStatus == NameStatus.READING -> Text("Reading name…", style = Parity.type.title, color = c.textSecondary)
                        else -> Text("Name not recognized — tap to type", style = Parity.type.body, color = c.textSecondary)
                    }
                    val original = card.originalName
                    if (original != null && original != name) {
                        Text(original, style = Parity.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    translationNote(card, labelLanguage)?.let { Text(it, style = Parity.type.caption, color = c.textSecondary) }
                }
                Icon(Icons.Rounded.Edit, contentDescription = "Edit name", tint = c.textSecondary, modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Chip("SALE", color = c.sale, selected = card.isPromo, onClick = onToggleSale)
                }
                item {
                    Chip(if (card.multiBuy != null) "DEAL" else "+ Deal", color = c.accent, selected = card.multiBuy != null, onClick = onDeal)
                }
                card.regularPrice?.let { regular ->
                    item { Chip("was ${MoneyFormat.format(regular, card.localCurrency)}", color = c.textSecondary) }
                }
                items(card.alternatives) { amount ->
                    Chip(MoneyFormat.format(amount, card.localCurrency), onClick = { onAlternative(amount) })
                }
            }

            card.otherCurrency?.let { other ->
                // The tag's own currency is used; the local one is a tap away if the symbol was misread.
                Spacer(Modifier.height(12.dp))
                Text("Priced in ${Currencies[card.localCurrency].name}", style = Parity.type.body, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(card.localCurrency.code, color = c.accent, selected = true)
                    Chip(other.code, onClick = { onCurrency(other) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Cancel", onCancel, Modifier.weight(1f), primary = false, height = 52.dp)
                PillButton("Buy", onBuy, Modifier.weight(1f))
            }
        }
    }
}

/** Why the name isn't read or in the user's language yet. */
private fun translationNote(card: ScanCard, labelLanguage: String?): String? {
    if (card.readerMissing) {
        val labels = labelLanguage?.let { Languages.find(it)?.englishName } ?: "these"
        return "Reading $labels labels needs a connection once, to get the text reader"
    }
    val source = card.translationFrom ?: return null
    val from = Languages.find(source)?.englishName ?: "this language"
    val packExists = LabelReading.offlineSource(source, Languages.all.map { it.tag }.toSet()) != null
    return when (card.translation) {
        TranslationStatus.TRANSLATING -> "Translating…"
        TranslationStatus.WAITING_FOR_NETWORK ->
            "Will translate when you're online" + if (packExists) " · or download $from in Settings to translate offline" else ""
        TranslationStatus.ONLINE_OFF ->
            if (packExists) "Download $from in Settings, or turn on online translation" else "Turn on online translation in Settings to translate $from"
        null -> null
    }
}

/** Converted price that counts up quickly to its exact value (design §4.2 motion). */
@Composable
private fun CountUpMoney(cardId: Long, amount: BigDecimal, currency: CurrencyCode) {
    val progress = remember(cardId) { Animatable(0f) }
    LaunchedEffect(cardId, amount) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(250))
    }
    val text = if (progress.value >= 1f) {
        MoneyFormat.format(amount, currency)
    } else {
        val partial = BigDecimal.fromDouble(amount.doubleValue(false) * progress.value)
        MoneyFormat.format(partial, currency)
    }
    Text(text, style = Parity.type.display, color = Parity.colors.textPrimary)
}

/** Collapsible strip of shopping-list items on Home (design §4.1). */
@Composable
private fun ListStrip(items: List<ShoppingListItemEntity>, onToggle: (ShoppingListItemEntity) -> Unit) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    val c = Parity.colors
    val open = items.count { !it.checked }
    GlassPanel(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("List", style = Parity.type.label, color = c.textPrimary)
                Text(" · ${if (open == 0) "all done" else "$open left"}", style = Parity.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, contentDescription = if (expanded) "Collapse list" else "Expand list", tint = c.textSecondary)
            }
            AnimatedVisibility(expanded) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    items(items.sortedBy { it.checked }, key = { it.id }) { item ->
                        val strike by animateFloatAsState(if (item.checked) 1f else 0f, tween(300), label = "strike")
                        Box(
                            Modifier.clip(CircleShape).background(c.surfaceRaised).clickable { onToggle(item) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                item.text,
                                style = Parity.type.label,
                                color = if (item.checked) c.textSecondary else c.textPrimary,
                                modifier = Modifier.strikeThrough(strike, c.up),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionPrompt(onAllow: () -> Unit, onType: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Parity.colors.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Parity reads price tags with your camera", style = Parity.type.headline, color = Parity.colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Nothing leaves your phone. You can also type prices in by hand.",
                style = Parity.type.body, color = Parity.colors.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            PillButton("Allow camera", onAllow, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            PillButton("Type a price", onType, Modifier.fillMaxWidth(), primary = false)
        }
    }
}
