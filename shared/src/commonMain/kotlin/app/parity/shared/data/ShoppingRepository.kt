package app.parity.shared.data

import app.parity.core.fx.FxRate
import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.core.money.divideMoney
import app.parity.core.money.toPlain
import app.parity.core.scan.Names
import app.parity.shared.platform.LocationFix
import app.parity.shared.util.newId
import app.parity.shared.util.now
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One cart row with everything the UI shows. */
data class CartItem(
    val line: CartLineEntity,
    val observation: PriceObservationEntity,
    val product: ProductEntity,
) {
    val quantity: BigDecimal get() = decimal(line.quantity)
    val unitLocal: BigDecimal get() = decimal(observation.price)
    val localCurrency: CurrencyCode get() = CurrencyCode(observation.localCurrency)
    val rate: BigDecimal? get() = observation.fxRate?.let(::decimal)
    val lineLocal: BigDecimal get() = unitLocal.multiply(quantity, MoneyMath)

    /** Converted with the rate locked in when the item was scanned (design §7). */
    val lineBase: BigDecimal? get() = rate?.let { lineLocal.divideMoney(it) }
}

data class PreviousSighting(val observation: PriceObservationEntity, val storeName: String?)

data class SessionDetail(
    val session: ShoppingSessionEntity,
    val storeName: String?,
    val lines: List<PurchaseLineEntity>,
)

/** Stores, products, price observations, the cart, finalizing and history (design §6.4, §7, §11). */
class ShoppingRepository(private val db: ParityDatabase) {
    private val stores = db.stores()
    private val products = db.products()
    private val observations = db.observations()
    private val cart = db.cart()
    private val sessions = db.sessions()

    // --- Stores (design §5) -------------------------------------------------------------------

    /** Nearest store within 75 m of [fix], or a new provisional store there. */
    suspend fun storeFor(fix: LocationFix?): StoreEntity? {
        val lat = fix?.lat ?: return null
        val lng = fix.lng ?: return null
        val all = stores.all()
        all.map { it to distanceMeters(lat, lng, it.lat, it.lng) }
            .filter { it.second <= 75.0 }
            .minByOrNull { it.second }
            ?.let { return it.first }
        val store = StoreEntity(newId(), "Store ${all.size + 1}", lat, lng, fix.countryCode, now())
        stores.upsert(store)
        return store
    }

    suspend fun renameStore(id: String, name: String) {
        stores.get(id)?.let { stores.upsert(it.copy(name = name.trim().ifEmpty { it.name })) }
    }

    suspend fun store(id: String?): StoreEntity? = id?.let { stores.get(it) }

    fun observeStores(): Flow<List<StoreEntity>> = stores.observeAll()

    // --- Products (design §6.4) -----------------------------------------------------------------

    /** Finds the product by barcode, then by exact name in this store, then by fuzzy name; else creates it. */
    suspend fun identifyProduct(barcode: String?, originalName: String?, storeId: String?): ProductEntity {
        barcode?.let { code -> products.byBarcode(code)?.let { return it } }
        val normalized = originalName?.let(Names::normalize)?.takeIf { it.length >= 3 }
        if (normalized != null) {
            val exact = products.byName(normalized, storeId)
            val fuzzy = exact ?: products.inStore(storeId)
                .filter { it.normalizedName != null }
                .map { it to Names.similarity(it.normalizedName!!, normalized) }
                .filter { it.second >= 0.92 }
                .maxByOrNull { it.second }?.first
            if (fuzzy != null) {
                if (barcode != null && fuzzy.barcode == null) products.upsert(fuzzy.copy(barcode = barcode))
                return fuzzy.copy(barcode = fuzzy.barcode ?: barcode)
            }
        }
        val product = ProductEntity(
            id = newId(), barcode = barcode, originalName = originalName?.trim(), normalizedName = normalized,
            originalLang = null, translatedName = null, translatedLang = null, userEditedName = null,
            category = null, storeId = storeId, createdAt = now(),
        )
        products.upsert(product)
        return product
    }

    suspend fun product(id: String): ProductEntity? = products.get(id)

    fun observeProduct(id: String): Flow<ProductEntity?> = products.observe(id)

    suspend fun saveTranslation(productId: String, originalLang: String?, translated: String?, translatedLang: String?) {
        products.get(productId)?.let {
            products.upsert(it.copy(originalLang = originalLang, translatedName = translated, translatedLang = translatedLang))
        }
    }

    suspend fun renameProduct(productId: String, name: String) {
        products.get(productId)?.let { products.upsert(it.copy(userEditedName = name.trim().ifEmpty { null })) }
    }

    // --- Observations (design §6, §9) -------------------------------------------------------------

    /**
     * Records a scan. Every locked scan is kept, bought or not (design §19 decision 2). A repeat
     * of the same product and price within 10 minutes reuses the earlier observation.
     */
    suspend fun recordObservation(
        product: ProductEntity,
        store: StoreEntity?,
        countryCode: String?,
        localCurrency: CurrencyCode,
        price: BigDecimal,
        regularPrice: BigDecimal?,
        isPromo: Boolean,
        baseCurrency: CurrencyCode,
        rate: FxRate?,
    ): PriceObservationEntity {
        val priceText = price.toPlain()
        observations.recentFor(product.id, now() - 10 * 60_000).firstOrNull {
            it.price == priceText && it.localCurrency == localCurrency.code && it.baseCurrency == baseCurrency.code
        }?.let { return it }
        val observation = PriceObservationEntity(
            id = newId(), productId = product.id, storeId = store?.id, sessionId = null, observedAt = now(),
            countryCode = countryCode, localCurrency = localCurrency.code, price = priceText,
            regularPrice = regularPrice?.toPlain(), isPromo = isPromo, promoSource = "AUTO",
            baseCurrency = baseCurrency.code, fxRate = rate?.value?.toPlain(), fxRateAt = rate?.publishedAtMs,
            fxFetchedAt = rate?.fetchedAtMs, fxProvider = rate?.provider, fxPivot = rate?.pivot?.code,
        )
        observations.upsert(observation)
        return observation
    }

    suspend fun observation(id: String): PriceObservationEntity? = observations.get(id)

    suspend fun updateObservation(id: String, transform: (PriceObservationEntity) -> PriceObservationEntity) {
        observations.get(id)?.let { observations.upsert(transform(it)) }
    }

    /** The previous sighting of this product in the same currencies, for the ▲/▼ indicator. */
    suspend fun previousSighting(observation: PriceObservationEntity): PreviousSighting? {
        val previous = observations.previousFor(
            observation.productId, observation.localCurrency, observation.baseCurrency, observation.id,
        ) ?: return null
        return PreviousSighting(previous, previous.storeId?.let { stores.get(it)?.name })
    }

    fun observeAllObservations(): Flow<List<PriceObservationEntity>> = observations.observeAll()

    suspend fun allProducts(): List<ProductEntity> = products.all()

    // --- Cart (design §7) ---------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val cartItems: Flow<List<CartItem>> = cart.observe().mapLatest { lines ->
        lines.mapNotNull { line ->
            val observation = observations.get(line.observationId) ?: return@mapNotNull null
            val product = products.get(observation.productId) ?: return@mapNotNull null
            CartItem(line, observation, product)
        }
    }

    suspend fun addToCart(observationId: String, quantity: BigDecimal): CartLineEntity {
        val existing = cart.byObservation(observationId)
        val line = existing?.copy(quantity = (decimal(existing.quantity) + quantity).toPlain())
            ?: CartLineEntity(newId(), observationId, quantity.toPlain(), now())
        cart.upsert(line)
        return line
    }

    suspend fun setQuantity(line: CartLineEntity, quantity: BigDecimal) {
        if (quantity.signum() <= 0) cart.delete(line) else cart.upsert(line.copy(quantity = quantity.toPlain()))
    }

    suspend fun removeFromCart(line: CartLineEntity) = cart.delete(line)

    suspend fun restoreCartLine(line: CartLineEntity) = cart.upsert(line)

    // --- Finalize and history (design §11) ---------------------------------------------------------

    /** Writes the cart as a shopping session in one transaction and empties the cart. */
    suspend fun finalize(items: List<CartItem>, baseCurrency: CurrencyCode, countryCode: String?): ShoppingSessionEntity? {
        if (items.isEmpty()) return null
        val sessionId = newId()
        val totalBase = items.mapNotNull { it.lineBase }.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        val totalsLocal = items.groupBy { it.localCurrency.code }
            .mapValues { (_, group) -> group.fold(BigDecimal.ZERO) { acc, item -> acc + item.lineLocal }.toPlain() }
        val session = ShoppingSessionEntity(
            id = sessionId,
            startedAt = items.minOf { it.line.addedAt },
            finalizedAt = now(),
            storeId = items.groupingBy { it.observation.storeId }.eachCount().maxByOrNull { it.value }?.key,
            countryCode = countryCode,
            baseCurrency = baseCurrency.code,
            totalBase = totalBase.toPlain(),
            totalsLocalJson = JsonObject(totalsLocal.mapValues { JsonPrimitive(it.value) }).toString(),
            itemCount = items.size,
        )
        val lines = items.map { item ->
            PurchaseLineEntity(
                id = newId(), sessionId = sessionId, productId = item.product.id, observationId = item.observation.id,
                quantity = item.line.quantity, unitPriceLocal = item.observation.price, localCurrency = item.observation.localCurrency,
                unitPriceBase = item.rate?.let { item.unitLocal.divideMoney(it).toPlain() }, baseCurrency = item.observation.baseCurrency,
                fxRate = item.observation.fxRate, isPromo = item.observation.isPromo,
                nameAtPurchase = item.product.originalName, nameTranslatedAtPurchase = item.product.displayName,
            )
        }
        db.useWriterConnectionTransaction {
            sessions.upsert(session)
            sessions.upsertLines(lines)
            items.forEach { observations.upsert(it.observation.copy(sessionId = sessionId)) }
            cart.deleteAll()
        }
        return session
    }

    fun sessionsBetween(from: Long, to: Long): Flow<List<ShoppingSessionEntity>> = sessions.observeBetween(from, to)

    suspend fun sessionDetail(id: String): SessionDetail? {
        val session = sessions.get(id) ?: return null
        return SessionDetail(session, session.storeId?.let { stores.get(it)?.name }, sessions.lines(id))
    }

    companion object {
        fun localTotals(json: String): Map<CurrencyCode, BigDecimal> = runCatching {
            Json.parseToJsonElement(json).jsonObject.map { (k, v) -> CurrencyCode(k) to decimal(v.jsonPrimitive.content) }.toMap()
        }.getOrDefault(emptyMap())

        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double?, lng2: Double?): Double {
            if (lat2 == null || lng2 == null) return Double.MAX_VALUE
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * PI / 180
            val dLng = (lng2 - lng1) * PI / 180
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
