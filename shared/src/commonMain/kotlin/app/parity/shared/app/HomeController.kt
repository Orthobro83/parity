package app.parity.shared.app

import app.parity.core.fx.FxIndicator
import app.parity.core.fx.FxRate
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Languages
import app.parity.core.money.MoneyFormat
import app.parity.core.money.decimal
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.MultiBuyOffer
import app.parity.core.scan.NameText
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.ParsedTag
import app.parity.core.scan.PriceCandidate
import app.parity.core.scan.PriceStabilizer
import app.parity.core.scan.PriceTagParser
import app.parity.core.transfer.QrTransfer
import app.parity.shared.data.CartItem
import app.parity.shared.data.PreviousSighting
import app.parity.shared.data.ProductEntity
import app.parity.shared.data.Settings
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class NameStatus { READING, DONE, NONE }

/** The result card on Home (design §4.1, §6, §9). */
data class ScanCard(
    val id: Long,
    val tag: ParsedTag,
    val localPrice: BigDecimal,
    val localCurrency: CurrencyCode,
    val baseCurrency: CurrencyCode,
    val rate: FxRate? = null,
    val rateStale: Boolean = false,
    val rateLoading: Boolean = true,
    val name: String? = null,
    val originalName: String? = null,
    val nameStatus: NameStatus = NameStatus.READING,
    val isPromo: Boolean = false,
    val regularPrice: BigDecimal? = null,
    val alternatives: List<BigDecimal> = emptyList(),
    /** Currency printed on the tag when it differs from the local one; the user picks which applies. */
    val printedCurrency: CurrencyCode? = null,
    val observationId: String? = null,
    val productId: String? = null,
    val indicator: FxIndicator? = null,
    val previous: PreviousSighting? = null,
    /** Multi-buy deal on the tag; [localPrice] stays the regular single-unit price. */
    val multiBuy: MultiBuyOffer? = null,
) {
    val basePrice: BigDecimal? get() = rate?.localToBase(localPrice)
}

data class CartSummary(
    val count: Int,
    val totalBase: BigDecimal,
    val baseCurrency: CurrencyCode,
    val localTotals: Map<CurrencyCode, BigDecimal>,
    /** Some lines were scanned without any exchange rate, so the base total is incomplete. */
    val missingRates: Boolean,
)

/** Scanning pipeline, result card, cart and finalize (design §6, §7, §11.1). */
class HomeController(private val graph: AppGraph) {
    private val scope = graph.scope
    private val frames = MutableSharedFlow<OcrFrame>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val stabilizer = PriceStabilizer(requiredFrames = 3)
    private var emptyFrames = 0
    private var cardCounter = 0L
    private var enrichJob: Job? = null

    private val _card = MutableStateFlow<ScanCard?>(null)
    val card: StateFlow<ScanCard?> = _card

    /** True while a sheet or dialog is open, so the camera doesn't replace the card underneath. */
    val paused = MutableStateFlow(false)

    private val _qrDetected = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val qrDetected: SharedFlow<String> = _qrDetected

    val cart: StateFlow<List<CartItem>> = graph.shopping.cartItems.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<CartSummary?> = combine(cart, graph.settings) { items, settings ->
        if (items.isEmpty() || settings == null) return@combine null
        CartSummary(
            count = items.size,
            totalBase = items.mapNotNull { it.lineBase }.fold(BigDecimal.ZERO) { acc, v -> acc + v },
            baseCurrency = settings.baseCurrency,
            localTotals = items.groupBy { it.localCurrency }.mapValues { (_, group) -> group.fold(BigDecimal.ZERO) { acc, i -> acc + i.lineLocal } },
            missingRates = items.any { it.rate == null },
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.Default) {
            frames.collect { frame -> process(frame) }
        }
        // Fetch the translation model for the local language before the first scan needs it.
        scope.launch {
            graph.settings
                .map { s -> s?.let { Languages.labelLanguageFor(it.country) to it.language } }
                .distinctUntilChanged()
                .collectLatest { pair ->
                    val (label, language) = pair ?: return@collectLatest
                    if (label != null && label != language) runCatching { graph.platform.text.prepare(label, language) }
                }
        }
    }

    /** Called from the camera's analysis thread. */
    fun onFrame(frame: OcrFrame) {
        frames.tryEmit(frame)
    }

    /** Called from the camera's analysis thread when a QR code is visible on Home. */
    fun onQrCode(text: String) {
        if (QrTransfer.looksLikeFrame(text)) _qrDetected.tryEmit(text)
    }

    private suspend fun process(frame: OcrFrame) {
        if (paused.value) return
        val settings = graph.settings.value ?: return
        val tag = PriceTagParser.parse(frame, settings.localCurrency)
        if (tag.price == null) {
            // Nothing price-like for a while: forget the last tag so re-scanning it shows the card again.
            if (++emptyFrames >= 8) stabilizer.reset()
            return
        }
        emptyFrames = 0
        val locked = stabilizer.offer(tag) ?: return
        withContext(Dispatchers.Main) { show(locked, settings) }
    }

    private fun show(tag: ParsedTag, settings: Settings) {
        val price = tag.price ?: return
        enrichJob?.cancel()
        val local = settings.localCurrency
        val printed = price.currency?.takeIf { it != local }
        val card = ScanCard(
            id = ++cardCounter,
            tag = tag,
            localPrice = price.amount,
            localCurrency = local,
            baseCurrency = settings.baseCurrency,
            // A typed-in name (frame 0) is always shown; OCR names only where the script reads reliably.
            name = tag.name.takeIf { tag.frameId == 0L || latinLabels(settings) },
            originalName = tag.name.takeIf { tag.frameId == 0L || latinLabels(settings) },
            isPromo = tag.isPromo,
            regularPrice = tag.regularPrice,
            alternatives = tag.alternatives.map { it.amount },
            printedCurrency = printed,
            multiBuy = tag.multiBuy,
        )
        _card.value = card
        graph.platform.haptics.tick()
        enrichJob = scope.launch { enrich(card.id, tag, settings, local, record = printed == null) }
    }

    private fun update(cardId: Long, transform: (ScanCard) -> ScanCard) {
        val current = _card.value ?: return
        if (current.id == cardId) _card.value = transform(current)
    }

    /** Rate, name (Tesseract for scripts ML Kit can't read), product identity, ▲/▼, translation. */
    private suspend fun enrich(cardId: Long, tag: ParsedTag, settings: Settings, local: CurrencyCode, record: Boolean) = coroutineScope {
        // Read the name (and, for scripts the fast recognizer can't read, the tag's wording) while the
        // rate is fetched; the frame must still be in the camera's cache.
        val refined = async { refineWithScriptOcr(tag, settings) }
        val name = async {
            // The script-aware pass reads the whole tag; its name skips promotion banners. Fall back
            // to a focused read of the name area when it found none.
            refined.await()?.name?.let { NameText.pickLines(it, Languages.labelLanguageFor(settings.country)) }
                ?: resolveName(tag, settings)
        }
        val rateResult = graph.rates.rate(settings.baseCurrency, local, settings.rateProvider, settings.rateApiKey)
        update(cardId) { it.copy(rate = rateResult.rate, rateStale = rateResult.stale, rateLoading = false) }

        refined.await()?.takeIf { it.differsFrom(tag) }?.let { better ->
            update(cardId) { card ->
                // Keep a price the shopper picked by hand; otherwise take the better reading.
                val priceUntouched = card.localPrice.compareTo(tag.price?.amount ?: card.localPrice) == 0
                card.copy(
                    localPrice = if (priceUntouched) better.price?.amount ?: card.localPrice else card.localPrice,
                    regularPrice = better.regularPrice ?: card.regularPrice,
                    isPromo = better.isPromo,
                    multiBuy = card.multiBuy ?: better.multiBuy,
                    alternatives = better.alternatives.map { it.amount },
                )
            }
        }
        val originalName = name.await()
        update(cardId) {
            it.copy(
                originalName = originalName,
                name = originalName,
                nameStatus = if (originalName == null) NameStatus.NONE else NameStatus.READING,
            )
        }
        if (!record) {
            update(cardId) { it.copy(nameStatus = if (originalName == null) NameStatus.NONE else NameStatus.DONE) }
            return@coroutineScope
        }

        val store = graph.shopping.storeFor(graph.location.lastFix.value)
        var product = graph.shopping.identifyProduct(tag.barcode, originalName, store?.id)
        val card = _card.value?.takeIf { it.id == cardId } ?: return@coroutineScope
        val observation = graph.shopping.recordObservation(
            product = product, store = store, countryCode = settings.country, localCurrency = local,
            price = card.localPrice, regularPrice = card.regularPrice, isPromo = card.isPromo,
            baseCurrency = settings.baseCurrency, rate = rateResult.rate, multiBuy = card.multiBuy,
        )
        val previous = graph.shopping.previousSighting(observation)
        val indicator = run {
            val prevRate = previous?.observation?.fxRate?.let(::decimal) ?: return@run null
            val nowRate = observation.fxRate?.let(::decimal) ?: return@run null
            FxIndicator.compute(prevRate, nowRate)
        }
        update(cardId) {
            it.copy(
                observationId = observation.id, productId = product.id, indicator = indicator, previous = previous,
                name = product.displayName ?: it.name,
                nameStatus = if (product.displayName == null) NameStatus.NONE else it.nameStatus,
            )
        }

        product = translateIfNeeded(product, settings)
        update(cardId) {
            it.copy(name = product.displayName, nameStatus = if (product.displayName == null) NameStatus.NONE else NameStatus.DONE)
        }
    }

    /**
     * Georgian (and other non-Latin) tags: re-read the area around the price with Tesseract so deal,
     * sale and per-kilo wording and the name are understood (design §14). Null when not needed or
     * not readable.
     */
    private suspend fun refineWithScriptOcr(tag: ParsedTag, settings: Settings): ParsedTag? {
        if (latinLabels(settings)) return null
        val price = tag.price ?: return null
        val tesseract = tesseractLanguages(settings) ?: return null
        val p = price.box
        if (p.width <= 0f || p.height <= 0f) return null // typed-in price: no image
        val area = Box(p.left - p.width * 1.2f, p.top - p.height * 2.5f, p.right + p.width * 1.2f, p.bottom + p.height * 3.2f)
        val lines = withTimeoutOrNull(10_000) {
            runCatching { graph.platform.camera.readLines(tag.frameId, area, tesseract) }.getOrNull()
        } ?: return null
        return withContext(Dispatchers.Default) { PriceTagParser.refine(tag, lines) }
    }

    private fun ParsedTag.differsFrom(original: ParsedTag): Boolean =
        multiBuy != original.multiBuy || isPromo != original.isPromo || regularPrice != original.regularPrice ||
            price?.amount?.compareTo(original.price?.amount ?: return true) != 0

    private fun tesseractLanguages(settings: Settings): String? = when (Languages.labelLanguageFor(settings.country)) {
        "ka" -> "kat+eng"
        "ru", "uk", "be" -> "rus+eng"
        else -> null
    }

    private suspend fun resolveName(tag: ParsedTag, settings: Settings): String? {
        // Typed in by hand (manual entry): take it as written.
        if (tag.frameId == 0L) return tag.name?.trim()?.ifEmpty { null }
        val labelLanguage = Languages.labelLanguageFor(settings.country)
        val tesseract = tesseractLanguages(settings)
        val region = tag.nameRegion
        if (tesseract != null) {
            // The Latin recognizer turns other scripts into gibberish, so don't fall back to it.
            if (region == null) return null
            val read = withTimeoutOrNull(8_000) {
                runCatching { graph.platform.camera.readRegion(tag.frameId, region, tesseract) }.getOrNull()
            }
            return NameText.pickLines(read, labelLanguage)
        }
        return cleanName(tag.name)
    }

    /** True when shelf labels here are in a script the fast (Latin) recognizer reads. */
    private fun latinLabels(settings: Settings): Boolean =
        Languages.labelLanguageFor(settings.country) !in setOf("ka", "ru", "uk", "be", "hy", "el", "he", "th", "zh", "ja", "ko")

    private fun cleanName(raw: String?): String? = NameText.pickLines(raw, null)

    private suspend fun translateIfNeeded(product: ProductEntity, settings: Settings): ProductEntity {
        val original = product.originalName ?: return product
        if (product.userEditedName != null) return product
        if (product.translatedLang == settings.language && product.translatedName != null) return product
        if (product.originalLang == settings.language) return product
        val text = graph.platform.text
        val source = runCatching { text.identifyLanguage(original) }.getOrNull()
            ?: Languages.labelLanguageFor(settings.country)
        if (source == null || source == settings.language) {
            graph.shopping.saveTranslation(product.id, source ?: product.originalLang, null, null)
            return product.copy(originalLang = source)
        }
        val translated = runCatching { text.translate(original, source, settings.language) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(original, ignoreCase = true) }
        graph.shopping.saveTranslation(product.id, source, translated, if (translated != null) settings.language else null)
        return product.copy(originalLang = source, translatedName = translated, translatedLang = if (translated != null) settings.language else null)
    }

    // --- Card actions -------------------------------------------------------------------------------

    fun cancel() {
        _card.value = null
    }

    /**
     * Adds the current card to the cart and crosses off a matching list item (design §7, §8.3).
     * [useDeal] is the shopper's answer when the tag has a multi-buy deal.
     */
    fun buy(quantity: BigDecimal, useDeal: Boolean = false) {
        val card = _card.value ?: return
        scope.launch {
            if (card.observationId == null) withTimeoutOrNull(8_000) { enrichJob?.join() }
            val current = _card.value?.takeIf { it.id == card.id } ?: card
            val observationId = current.observationId
            if (observationId == null) {
                graph.messages.show("Still reading this tag — try again in a moment.")
                return@launch
            }
            graph.shopping.addToCart(observationId, quantity, multiBuy = useDeal && current.multiBuy != null)
            val product = current.productId?.let { graph.shopping.product(it) }
            val names = listOfNotNull(product?.userEditedName, product?.translatedName, product?.originalName, current.name)
            graph.listController.activeListIdOrNull()?.let { listId ->
                graph.lists.checkOffPurchase(listId, names, observationId)
            }
            graph.platform.haptics.success()
            if (_card.value?.id == card.id) _card.value = null
        }
    }

    /** The user picked a different number from the tag (design §6.1). */
    fun chooseAlternative(amount: BigDecimal) {
        val card = _card.value ?: return
        val previousPrice = card.localPrice
        _card.value = card.copy(localPrice = amount, alternatives = (card.alternatives - amount) + previousPrice)
        card.observationId?.let { id ->
            scope.launch { graph.shopping.updateObservation(id) { it.copy(price = amount.toPlain()) } }
        }
    }

    /** Manual SALE toggle; overrides auto-detection (design §6.3). */
    fun setPromo(isPromo: Boolean) {
        val card = _card.value ?: return
        _card.value = card.copy(isPromo = isPromo)
        card.observationId?.let { id ->
            scope.launch { graph.shopping.updateObservation(id) { it.copy(isPromo = isPromo, promoSource = "USER") } }
        }
    }

    /** Adds, corrects or removes the tag's multi-buy deal (for tags the camera misread). */
    fun setMultiBuy(offer: MultiBuyOffer?) {
        val card = _card.value ?: return
        _card.value = card.copy(multiBuy = offer)
        card.observationId?.let { id ->
            scope.launch { graph.shopping.updateObservation(id) { it.copy(multiBuyJson = offer?.toJson()) } }
        }
    }

    fun rename(name: String) {
        val card = _card.value ?: return
        _card.value = card.copy(name = name, nameStatus = NameStatus.DONE)
        card.productId?.let { id -> scope.launch { graph.shopping.renameProduct(id, name) } }
    }

    /** Resolves a tag printed in another currency than the local one (design §6.1). */
    fun chooseCurrency(currency: CurrencyCode) {
        val card = _card.value ?: return
        val settings = graph.settings.value ?: return
        enrichJob?.cancel()
        val updated = card.copy(localCurrency = currency, printedCurrency = null, rate = null, rateLoading = true, nameStatus = NameStatus.READING)
        _card.value = updated
        enrichJob = scope.launch { enrich(updated.id, card.tag, settings, currency, record = true) }
    }

    /** Price entry without the camera: permission denied, or a tag the camera can't read. */
    fun manualEntry(price: BigDecimal, name: String?, deal: MultiBuyOffer? = null) {
        val settings = graph.settings.value ?: return
        val candidate = PriceCandidate(price, 2, null, price.toPlain(), Box(0f, 0f, 0f, 0f), false, 1.0)
        val tag = ParsedTag.EMPTY.copy(price = candidate, name = name?.trim()?.ifEmpty { null }, multiBuy = deal)
        stabilizer.reset()
        show(tag, settings)
    }

    /** Picks a photo of a price tag and reads it like a camera frame. */
    fun scanPhoto() {
        val settings = graph.settings.value ?: return
        scope.launch {
            val bytes = graph.platform.files.open(listOf("image/*")) ?: return@launch
            val frame = graph.platform.camera.scanImage(bytes)
            val tag = frame?.let { withContext(Dispatchers.Default) { PriceTagParser.parse(it, settings.localCurrency) } }
            if (tag?.price == null) {
                graph.messages.show("No price found in that photo")
                return@launch
            }
            stabilizer.reset()
            show(tag, settings)
        }
    }

    // --- Cart (design §7) -------------------------------------------------------------------------

    fun setQuantity(item: CartItem, quantity: BigDecimal) {
        scope.launch { graph.shopping.setQuantity(item.line, quantity) }
    }

    fun remove(item: CartItem) {
        scope.launch {
            graph.shopping.removeFromCart(item.line)
            graph.lists.uncheckPurchase(item.observation.id)
            graph.messages.show("Removed ${item.product.displayName ?: "item"}", "Undo") {
                graph.shopping.restoreCartLine(item.line)
                graph.listController.activeListIdOrNull()?.let { listId ->
                    graph.lists.checkOffPurchase(listId, listOfNotNull(item.product.displayName, item.product.originalName), item.observation.id)
                }
            }
        }
    }

    // --- Finalize (design §11.1) --------------------------------------------------------------------

    suspend fun unpurchasedListItems(): List<String> {
        val listId = graph.listController.activeListIdOrNull() ?: return emptyList()
        return graph.lists.openItems(listId).map { it.text }
    }

    fun finalize() {
        val settings = graph.settings.value ?: return
        scope.launch {
            val items = cart.value
            val session = graph.shopping.finalize(items, settings.baseCurrency, settings.country) ?: return@launch
            graph.platform.haptics.success()
            val total = MoneyFormat.format(decimal(session.totalBase), settings.baseCurrency)
            graph.messages.show("Saved ${items.size} ${if (items.size == 1) "item" else "items"} ($total) to History")
        }
    }
}
