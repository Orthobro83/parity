package app.parity.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parity.core.fx.FxIndicator
import app.parity.core.fx.formatPercent
import app.parity.core.fx.formatRate
import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyFormat
import app.parity.core.money.decimal
import app.parity.core.money.divideMoney
import app.parity.shared.app.AppGraph
import app.parity.shared.data.PriceObservationEntity
import app.parity.shared.data.Settings
import app.parity.shared.ui.components.FxBadge
import app.parity.shared.ui.components.SectionHeader
import app.parity.shared.ui.formatDate
import app.parity.shared.ui.plural
import app.parity.shared.ui.theme.Parity
import com.ionspin.kotlin.bignum.decimal.BigDecimal

private data class PairTrend(val base: CurrencyCode, val local: CurrencyCode, val first: PriceObservationEntity, val last: PriceObservationEntity, val count: Int)

private data class ProductTrend(val name: String, val first: PriceObservationEntity, val last: PriceObservationEntity, val count: Int)

/**
 * Phase 1 analytics: how your currency has done against each local one, and per-product changes in
 * your currency with sale prices excluded (design §10). Full charts come in M5.
 */
@Composable
fun AnalyticsScreen(graph: AppGraph, settings: Settings) {
    val observations by remember { graph.shopping.observeAllObservations() }.collectAsState(emptyList())
    val products by produceState(emptyMap<String, String>(), observations) {
        value = graph.shopping.allProducts().associate { it.id to (it.displayName ?: "Unnamed item") }
    }
    val c = Parity.colors

    val withRates = observations.filter { it.fxRate != null }
    val pairs = withRates.groupBy { it.baseCurrency to it.localCurrency }
        .filter { it.key.first != it.key.second }
        .map { (key, obs) -> PairTrend(CurrencyCode(key.first), CurrencyCode(key.second), obs.first(), obs.last(), obs.size) }
    // Sale prices are noise for price tracking: use the regular price if the tag showed one, else skip.
    val productTrends = withRates
        .mapNotNull { o -> if (!o.isPromo) o else o.regularPrice?.let { o.copy(price = it) } }
        .filter { it.baseCurrency == settings.baseCurrency.code }
        .groupBy { it.productId }
        .filter { it.value.size >= 2 }
        .map { (id, obs) -> ProductTrend(products[id] ?: "…", obs.first(), obs.last(), obs.size) }
        .sortedByDescending { it.last.observedAt }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Analytics", style = Parity.type.headline)
            Text(
                "${plural(observations.size, "price")} recorded · ${plural(observations.map { it.productId }.distinct().size, "product")}",
                style = Parity.type.body, color = c.textSecondary,
            )
            SectionHeader("Your currency vs local")
            if (pairs.isEmpty()) Text("Scan a few prices abroad to see this.", style = Parity.type.body, color = c.textSecondary)
        }
        items(pairs, key = { "${it.base}-${it.local}" }) { pair ->
            val indicator = FxIndicator.compute(decimal(pair.first.fxRate!!), decimal(pair.last.fxRate!!))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${pair.base.code} → ${pair.local.code}", style = Parity.type.title)
                        Text("Since ${formatDate(pair.first.observedAt)} · ${pair.count} scans", style = Parity.type.caption, color = c.textSecondary)
                        Text(
                            "1 ${pair.base.code} = ${formatRate(decimal(pair.first.fxRate!!))} → ${formatRate(decimal(pair.last.fxRate!!))} ${pair.local.code}",
                            style = Parity.type.priceSmall, color = c.textSecondary,
                        )
                    }
                    FxBadge(indicator)
                }
            }
        }
        item {
            SectionHeader("Prices in ${settings.baseCurrency.code} (sales excluded)")
            if (productTrends.isEmpty()) Text("Products scanned on two or more visits appear here.", style = Parity.type.body, color = c.textSecondary)
        }
        items(productTrends, key = { it.first.productId }) { trend ->
            val firstBase = decimal(trend.first.price).divideMoney(decimal(trend.first.fxRate!!))
            val lastBase = decimal(trend.last.price).divideMoney(decimal(trend.last.fxRate!!))
            val change = lastBase.divideMoney(firstBase) - BigDecimal.ONE
            val shelfChange = decimal(trend.last.price).divideMoney(decimal(trend.first.price)) - BigDecimal.ONE
            // How much of the change came from the exchange rate alone: R_then / R_now − 1.
            val currencyEffect = decimal(trend.first.fxRate!!).divideMoney(decimal(trend.last.fxRate!!)) - BigDecimal.ONE
            Card {
                Text(trend.name, style = Parity.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${MoneyFormat.format(firstBase, settings.baseCurrency)} → ${MoneyFormat.format(lastBase, settings.baseCurrency)}  (${formatPercent(change)})",
                    style = Parity.type.priceSmall,
                )
                Text(
                    "Shelf price ${formatPercent(shelfChange)} · currency effect ${formatPercent(currencyEffect)} · ${trend.count} visits",
                    style = Parity.type.caption, color = c.textSecondary,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Parity.colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}
