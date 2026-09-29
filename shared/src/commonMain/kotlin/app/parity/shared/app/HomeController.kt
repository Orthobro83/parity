package app.parity.shared.app

import app.parity.core.fx.FxIndicator
import app.parity.core.fx.FxRate
import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Languages
import app.parity.core.money.MoneyFormat
import app.parity.core.money.decimal
import app.parity.core.money.divideMoney
import app.parity.core.money.parseDecimal
import app.parity.core.money.toPlain
import app.parity.core.scan.AiTagReading
import app.parity.core.scan.Box
import app.parity.core.scan.LabelReading
import app.parity.core.scan.MultiBuyOffer
import app.parity.core.scan.NameText
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrMemory
import app.parity.core.scan.OcrLine
import app.parity.core.scan.ParsedTag
import app.parity.core.scan.PriceCandidate
import app.parity.core.scan.PriceStabilizer
import app.parity.core.scan.PriceTagParser
import app.parity.core.scan.StorePhrases
import app.parity.core.scan.TagQuad
import app.parity.core.scan.TextScript
import app.parity.core.transfer.QrTransfer
import app.parity.shared.data.CartItem
import app.parity.shared.data.PreviousSighting
import app.parity.shared.data.ProductEntity
import app.parity.shared.data.Settings
import app.parity.shared.translate.AiEndpoint
import app.parity.shared.util.now
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

/**
 * Why a name isn't in the user's language yet: being translated; waiting for a connection (no
 * offline pack here); or online translation is off and there's no pack.
 */
enum class TranslationStatus { TRANSLATING, WAITING_FOR_NETWORK, ONLINE_OFF }

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
    /**
     * When the tag shows a currency other than the local one (a ¥ tag while set to euros), the price
     * is read in the tag's currency and this is the local one, a tap away in case the symbol was misread.
     */
    val otherCurrency: CurrencyCode? = null,
    val observationId: String? = null,
    val productId: String? = null,
    val indicator: FxIndicator? = null,
    val previous: PreviousSighting? = null,
    /** Multi-buy deal on the tag; [localPrice] stays the regular single-unit price. */
    val multiBuy: MultiBuyOffer? = null,
    val translation: TranslationStatus? = null,
    /** Language the name is being translated from (a BCP-47 tag). */
    val translationFrom: String? = null,
    /** The labels' script needs a text reader that hasn't downloaded yet (no connection). */
    val readerMissing: Boolean = false,
    /** From a photo, the shutter or typed in: the live camera doesn't replace it. */
    val pinned: Boolean = false,
    /** What the on-device reader printed, kept so a confirmed AI reading can be learned against it. */
    val deviceName: String? = null,
    /** A tap on AI is in flight. The rest of the card stays usable. */
    val aiBusy: Boolean = false,
    /** An AI reading has replaced the on-device one. */
    val aiReplaced: Boolean = false,
    /** The shopper marked that AI reading right. A second tap does not learn it again. */
    val aiConfirmed: Boolean = false,
    /** Store chrome from the AI reading. Learned only with the checkmark, and only if it was on the crop. */
    val aiChrome: List<String> = emptyList(),
) {
    val basePrice: BigDecimal? get() = rate?.localToBase(localPrice)
}

/** A cropped label and the text the phone read on that same crop. */
private class LabelCrop(val ocr: String, val jpeg: ByteArray)

/** The tag the live camera is reading, as outlined in the frame's pixels (design §6.1). */
data class Aim(val quad: TagQuad, val frameWidth: Int, val frameHeight: Int)

data class CartSummary(
    val count: Int,
    val totalBase: BigDecimal,
    val baseCurrency: CurrencyCode,
    val localTotals: Map<CurrencyCode, BigDecimal>,
    /**
     * Local units per 1 base unit behind each converted total: the rates the items were scanned
     * at, weighted by what they cost (one rate, when they were all scanned the same day).
     */
    val rates: Map<CurrencyCode, BigDecimal>,
    /** Some lines were scanned without any exchange rate, so the base total is incomplete. */
    val missingRates: Boolean,
)

/** Scanning pipeline, result card, cart and finalize (design §6, §7, §11.1). */
class HomeController(private val graph: AppGraph) {
    private val scope = graph.scope
    private val frames = MutableSharedFlow<OcrFrame>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val stabilizer = PriceStabilizer(requiredFrames = 3)
    /** The store's own slogans and name, learnt from tag after tag this session (design §6.2). */
    private val storePhrases = StorePhrases()
    /**
     * Misread lines and confirmed words kept on this phone. Applied before a tag is parsed.
     * The recognizers' weights are not changed: ML Kit has no word list, and Tesseract is handed
     * the words ([app.parity.shared.platform.CameraScanner.noteReaderWords]).
     */
    private val ocrMemory = OcrMemory()
    private var emptyFrames = 0
    private var cardCounter = 0L
    private var enrichJob: Job? = null
    /** The shopper's tap on AI. Not started by the scan, and cancelled when the card goes away. */
    private var aiJob: Job? = null
    /** Bumped when that tap is dropped, so a late reply cannot land on the next card. */
    private var aiToken = 0

    private val _card = MutableStateFlow<ScanCard?>(null)
    val card: StateFlow<ScanCard?> = _card

    private val _capturing = MutableStateFlow(false)
    /** True while the shutter's photo is being taken and read. */
    val capturing: StateFlow<Boolean> = _capturing

    private val _aim = MutableStateFlow<Aim?>(null)
    /** The outline of the tag the live camera is reading, or null when it isn't on one. */
    val aim: StateFlow<Aim?> = _aim

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
            rates = items.filter { it.lineBase != null && it.localCurrency != settings.baseCurrency }
                .groupBy { it.localCurrency }
                .mapNotNull { (code, group) ->
                    val base = group.fold(BigDecimal.ZERO) { acc, i -> acc + i.lineBase!! }
                    val local = group.fold(BigDecimal.ZERO) { acc, i -> acc + i.lineLocal }
                    // A cart of zero-cost lines has no rate to show; fall back to the last one scanned.
                    val rate = if (base.signum() > 0) local.divideMoney(base) else group.last().rate
                    rate?.let { code to it }
                }.toMap(),
            missingRates = items.any { it.rate == null },
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.Default) {
            runCatching { graph.platform.camera.loadReadingNotes() }.getOrNull()?.let { notes ->
                ocrMemory.restore(notes)
                ocrMemory.chrome.forEach(storePhrases::learn)
                if (ocrMemory.userWords.isNotEmpty()) runCatching { graph.platform.camera.noteReaderWords(ocrMemory.userWords) }
            }
            frames.collect { frame -> process(frame) }
        }
        // Read labels here with the right recognizer, and fetch the text reader for scripts that need
        // one (a few MB) before the first scan does.
        scope.launch {
            graph.settings
                .map { s -> s?.labelLanguage }
                .distinctUntilChanged()
                .collectLatest { label ->
                    graph.platform.camera.script = LabelReading.fastScript(label) ?: TextScript.LATIN
                    LabelReading.tesseractLanguages(label)?.let { runCatching { graph.platform.camera.prepareReader(it) } }
                }
        }
        // Names scanned while offline are translated when that becomes possible.
        scope.launch {
            graph.settings.map { it?.translateOnline to it?.language }.distinctUntilChanged().collectLatest { (online, _) ->
                if (online != null) translatePending()
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
        // A card the shopper asked for (photo, shutter, typed) stays until they're done with it.
        if (paused.value || _card.value?.pinned == true) {
            _aim.value = null
            return
        }
        val settings = graph.settings.value ?: return
        // Strict: only numbers written like shelf prices, so random text in view makes no card.
        val tag = PriceTagParser.parse(ocrMemory.apply(frame), settings.localCurrency, strict = true, storePhrases = storePhrases.known)
        _aim.value = tag.tagQuad?.let { Aim(it, frame.width, frame.height) }
        if (tag.price == null) {
            // Nothing price-like for a while: forget the last tag so re-scanning it shows the card again.
            if (++emptyFrames >= 8) stabilizer.reset()
            return
        }
        emptyFrames = 0
        val locked = stabilizer.offer(tag) ?: return
        withContext(Dispatchers.Main) { show(locked, settings, live = true) }
    }

    /**
     * [live]: locked on by the live camera, so a sharper still of the tag can be taken for small
     * print. Other cards (photo, shutter, typed) are pinned against the live camera.
     */
    private fun show(tag: ParsedTag, settings: Settings, live: Boolean = false) {
        val price = tag.price ?: return
        enrichJob?.cancel()
        aiToken++
        aiJob?.cancel()
        // A currency printed on the tag wins over the local one.
        val currency = price.currency ?: settings.localCurrency
        val card = ScanCard(
            id = ++cardCounter,
            tag = tag,
            localPrice = price.amount,
            localCurrency = currency,
            baseCurrency = settings.baseCurrency,
            // A typed-in name (frame 0) is always shown; OCR names only where the script reads reliably.
            name = tag.name.takeIf { tag.frameId == 0L || fastReadsLabels(settings) },
            originalName = tag.name.takeIf { tag.frameId == 0L || fastReadsLabels(settings) },
            isPromo = tag.isPromo,
            regularPrice = tag.regularPrice,
            alternatives = tag.alternatives.map { it.amount },
            otherCurrency = settings.localCurrency.takeIf { it != currency },
            multiBuy = tag.multiBuy,
            pinned = !live,
        )
        _card.value = card
        graph.platform.haptics.tick()
        remember(tag, tag.lines)
        enrichJob = scope.launch { enrich(card.id, tag, settings, currency, live) }
    }

    /** Notes the lines of a tag read within its outline, where they're its own (design §6.2). */
    private fun remember(tag: ParsedTag, lines: List<OcrLine>) {
        val price = tag.price ?: return
        if (tag.tagQuad == null) return
        storePhrases.record(price.amount.toStringExpanded() + (price.currency?.code ?: ""), lines)
    }

    private fun update(cardId: Long, transform: (ScanCard) -> ScanCard) {
        val current = _card.value ?: return
        if (current.id == cardId) _card.value = transform(current)
    }

    /**
     * Once the shopper has asked AI to replace this card, a late on-device pass must not put the
     * old price or name back. The sighting id and the rate still come from that pass.
     */
    private fun ScanCard.keepingAiReading(next: ScanCard): ScanCard {
        if (!aiReplaced) return next
        return next.copy(
            localPrice = localPrice,
            regularPrice = regularPrice,
            isPromo = isPromo,
            multiBuy = multiBuy,
            alternatives = alternatives,
            originalName = originalName,
            name = name,
            nameStatus = nameStatus,
            translation = translation,
            translationFrom = translationFrom,
            deviceName = deviceName,
            aiBusy = aiBusy,
            aiReplaced = aiReplaced,
            aiConfirmed = aiConfirmed,
            aiChrome = aiChrome,
        )
    }

    /**
     * Rate, name (Tesseract for scripts ML Kit can't read), product identity, ▲/▼, translation.
     * [knownName] is a name already read for this card, which is kept rather than read again.
     */
    private suspend fun enrich(
        cardId: Long,
        tag: ParsedTag,
        settings: Settings,
        local: CurrencyCode,
        live: Boolean,
        knownName: String? = null,
    ) = coroutineScope {
        // For scripts the fast recognizer can't read, the name and wording are read by Tesseract
        // (its model downloaded if needed) only from a full-resolution picture: on a live lock, a
        // still taken then (design §6.2). Live frames show that small print only 15–25 px tall, so
        // with no still there's no name rather than a guess at one.
        val needsReader = knownName == null && tag.frameId != 0L && !fastReadsLabels(settings)
        val reader = async {
            if (!needsReader) return@async null
            readerFor(settings).also { if (it == null) update(cardId) { card -> card.copy(readerMissing = true) } }
        }
        val source = async { if (live && needsReader) stillOf(tag, settings) else tag }
        // Read the name (and, for those scripts, the tag's wording) while the rate is fetched,
        // showing each as soon as it's ready.
        val refined = async {
            if (knownName != null) return@async null
            val tesseract = reader.await() ?: return@async null
            refineWithScriptOcr(source.await() ?: return@async null, settings, tesseract)?.also { better ->
                if (better.differsFrom(tag)) update(cardId) { card ->
                    // Keep a price the shopper picked by hand; otherwise take the better reading.
                    val priceUntouched = card.localPrice.compareTo(tag.price?.amount ?: card.localPrice) == 0
                    card.keepingAiReading(
                        card.copy(
                            localPrice = if (priceUntouched) better.price?.amount ?: card.localPrice else card.localPrice,
                            regularPrice = better.regularPrice ?: card.regularPrice,
                            // A sale either pass saw stands (the script pass may not read a banner the first
                            // pass did), unless the script pass found it was a multi-buy deal's wording.
                            isPromo = if (better.multiBuy != null) better.isPromo else better.isPromo || card.isPromo,
                            multiBuy = card.multiBuy ?: better.multiBuy,
                            alternatives = better.alternatives.map { it.amount },
                        ),
                    )
                }
            }
        }
        val name = async {
            (knownName ?: source.await()?.let { resolveName(it, refined.await(), settings, reader.await()) }).also { originalName ->
                update(cardId) { card ->
                    card.keepingAiReading(
                        card.copy(
                            originalName = originalName,
                            name = originalName,
                            nameStatus = if (originalName == null) NameStatus.NONE else NameStatus.READING,
                        ),
                    )
                }
            }
        }
        val rateResult = graph.rates.rate(settings.baseCurrency, local, settings.rateProvider, settings.rateApiKey)
        update(cardId) { it.copy(rate = rateResult.rate, rateStale = rateResult.stale, rateLoading = false) }
        val originalName = name.await()
        val store = graph.shopping.storeFor(graph.location.lastFix.value)
        // The name on the card wins once AI has replaced it; otherwise this is the on-device reading.
        val named = _card.value?.takeIf { it.id == cardId && it.aiReplaced }?.originalName ?: originalName
        var product = graph.shopping.identifyProduct(tag.barcode, named, store?.id)
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
        update(cardId) { card ->
            card.keepingAiReading(
                card.copy(
                    observationId = observation.id, productId = product.id, indicator = indicator, previous = previous,
                    name = product.displayName ?: card.name,
                    nameStatus = if (product.displayName == null) NameStatus.NONE else card.nameStatus,
                ),
            )
        }

        product = translateIfNeeded(product, settings) { status, from ->
            update(cardId) { card -> card.keepingAiReading(card.copy(translation = status, translationFrom = from)) }
        }
        update(cardId) { card ->
            card.keepingAiReading(
                card.copy(name = product.displayName, nameStatus = if (product.displayName == null) NameStatus.NONE else NameStatus.DONE),
            )
        }
        // A connection is back if this one went through: catch up on names scanned without one.
        if (product.translatedLang != null && now() - lastPendingRun > 2 * 60_000L) translatePending()
    }

    /**
     * Georgian (and other non-Latin) tags: re-read the area around the price with Tesseract so deal,
     * sale and per-kilo wording and the name are understood (design §14). Null when not needed or
     * not readable.
     */
    private suspend fun refineWithScriptOcr(tag: ParsedTag, settings: Settings, tesseract: String): ParsedTag? {
        val price = tag.price ?: return null
        val p = price.box
        val area = PriceTagParser.scriptArea(tag) ?: return null
        // A tag's smaller text is about half the height of its price digits. With its outline found,
        // the tag is read straightened, bands and all, rather than an area grown from the price.
        val lines = withTimeoutOrNull(12_000) {
            runCatching {
                graph.platform.camera.readLines(tag.frameId, area, tesseract, textHeight = p.height * 0.5f, quad = tag.tagQuad?.padded())
            }.getOrNull()
        } ?: return null
        remember(tag, lines)
        // The price's own crop with digits, separators and currency signs only, to confirm its digits.
        val priceLines = withTimeoutOrNull(4_000) {
            runCatching {
                graph.platform.camera.readPrice(tag.frameId, p, tesseract, PriceTagParser.priceCharacters(settings.localCurrency))
            }.getOrNull()
        }.orEmpty()
        return withContext(Dispatchers.Default) {
            PriceTagParser.refine(tag, lines, settings.localCurrency, settings.labelLanguage, priceLines, storePhrases.known)
        }
    }

    private fun ParsedTag.differsFrom(original: ParsedTag): Boolean =
        multiBuy != original.multiBuy || isPromo != original.isPromo || regularPrice != original.regularPrice ||
            price?.amount?.compareTo(original.price?.amount ?: return true) != 0

    /**
     * The Tesseract languages for the labels here, once their models are on the phone (downloading
     * them if needed), or null when the fast recognizer reads them or the models can't be had.
     */
    private suspend fun readerFor(settings: Settings): String? {
        val languages = LabelReading.tesseractLanguages(settings.labelLanguage) ?: return null
        val ready = withTimeoutOrNull(30_000) { runCatching { graph.platform.camera.prepareReader(languages) }.getOrDefault(false) } ?: false
        return languages.takeIf { ready }
    }

    /**
     * The product name as printed. Where labels are in a script the fast (Latin) recognizer can't
     * read, only the script-aware engine's reading counts: the fast recognizer turns the name into
     * gibberish and may pick up stray Latin text elsewhere in view (design §14).
     */
    private suspend fun resolveName(tag: ParsedTag, refined: ParsedTag?, settings: Settings, tesseract: String?): String? {
        // Typed in by hand (manual entry): take it as written.
        if (tag.frameId == 0L) return tag.name?.trim()?.ifEmpty { null }
        val language = settings.labelLanguage
        if (fastReadsLabels(settings)) return NameText.pickLines(tag.name, null)
        tesseract ?: return null
        // The pass over the whole tag, whose name skips deal and promotion wording.
        val fromTag = refined?.name?.let { NameText.pickLines(it, language) }
        // A name in the label's script has a word of it, not stray letters of it among OCR junk.
        if (NameText.isScriptName(fromTag, language) && !readsPartly(fromTag, refined?.nameBox, tag.lines)) return fromTag
        // A focused read of the name area, for when that pass missed the line or part of it.
        val textHeight = tag.nameBox?.height?.takeIf { it > 0f } ?: tag.price?.box?.height?.let { it * 0.5f }
        val focused = tag.nameRegion?.let { region ->
            withTimeoutOrNull(8_000) { runCatching { graph.platform.camera.readRegion(tag.frameId, region, tesseract, textHeight) }.getOrNull() }
        }?.let { PriceTagParser.pickName(it, language) }
        // The fuller reading in the label's script wins.
        listOfNotNull(fromTag, focused).filter { NameText.isScriptName(it, language) }
            .maxByOrNull { NameText.scriptLetters(it, language) }
            ?.let { return it }
        // Only Latin text read on the tag: a brand such as "Pringles" if the fast recognizer, which
        // reads Latin well, saw the same there; otherwise it's the label's script misread as Latin.
        val latin = tag.name?.let { NameText.pickLines(it, null) } ?: return null
        return latin.takeIf { listOfNotNull(fromTag, focused).any { reading -> NameText.sameLatinText(reading, latin) } }
    }

    /**
     * A full-resolution photo of the tag the live scan locked on, for reading small print in scripts
     * like Georgian. Null when the camera can't take one, or has already moved to something else.
     */
    private suspend fun stillOf(tag: ParsedTag, settings: Settings): ParsedTag? {
        val price = tag.price ?: return null
        // The card already shows the price, so there's time: a budget phone takes a few seconds.
        val frame = withTimeoutOrNull(10_000) { runCatching { graph.platform.camera.capture(stillOnly = true) }.getOrNull() } ?: return null
        val still = withContext(Dispatchers.Default) {
            PriceTagParser.parse(ocrMemory.apply(frame), settings.localCurrency, storePhrases = storePhrases.known)
        }
        return still.takeIf { it.price?.amount?.compareTo(price.amount) == 0 }
    }

    /**
     * True when the script-aware engine read only part of the line the fast recognizer found in the
     * same place: the fast one can't read the script, but it puts out about one character per glyph.
     */
    private fun readsPartly(name: String?, box: Box?, latinLines: List<OcrLine>): Boolean {
        if (name == null || box == null) return false
        val latin = latinLines.filter { line ->
            minOf(line.box.bottom, box.bottom) - maxOf(line.box.top, box.top) > 0.5f * minOf(line.box.height, box.height)
        }.maxByOrNull { it.box.horizontalOverlap(box) } ?: return false
        return name.count { !it.isWhitespace() } < latin.text.count { !it.isWhitespace() } * 0.7f
    }

    /** True when the fast recognizer reads the labels here: Latin, Chinese, Japanese, Korean or Devanagari. */
    private fun fastReadsLabels(settings: Settings): Boolean = LabelReading.fastScript(settings.labelLanguage) != null

    /**
     * Translates a product's name into the user's language (design §6.2): with an offline pack if
     * one is on the phone, otherwise online. [onStatus] shows progress on the card; names that can't
     * be translated now are retried later ([translatePending]).
     */
    private suspend fun translateIfNeeded(
        product: ProductEntity,
        settings: Settings,
        onStatus: (TranslationStatus?, String?) -> Unit = { _, _ -> },
    ): ProductEntity {
        val original = product.originalName ?: return product
        if (product.userEditedName != null) return product
        // Done for this language, including names with nothing to translate.
        if (product.translatedLang == settings.language) return product
        // A chopped banner or a smashed imprint is not a name. Translating it invents one
        // ("OFE" became a German institute) and the card then asks for a pack it doesn't need.
        if (!NameText.worthTranslating(original)) {
            graph.shopping.saveTranslation(product.id, product.originalLang, null, settings.language)
            return product.copy(translatedName = null, translatedLang = settings.language)
        }
        if (product.originalLang == settings.language) return product
        val source = sourceLanguage(original, settings)
        if (source == null || Languages.base(source) == settings.language) {
            graph.shopping.saveTranslation(product.id, source ?: product.originalLang, null, settings.language)
            return product.copy(originalLang = source ?: product.originalLang, translatedName = null, translatedLang = settings.language)
        }
        onStatus(TranslationStatus.TRANSLATING, source)
        val result = translate(original, source, settings)
        if (result == null) {
            // No offline pack, and no connection (or online translation is off): keep the name as read.
            graph.shopping.saveTranslation(product.id, source, null, null)
            onStatus(if (settings.translateOnline) TranslationStatus.WAITING_FOR_NETWORK else TranslationStatus.ONLINE_OFF, source)
            return product.copy(originalLang = source, translatedName = null, translatedLang = null)
        }
        val translated = result.takeIf { it.isNotBlank() && !it.equals(original, ignoreCase = true) }
        graph.shopping.saveTranslation(product.id, source, translated, settings.language)
        onStatus(null, null)
        return product.copy(originalLang = source, translatedName = translated, translatedLang = settings.language)
    }

    /**
     * [text] in the user's language: offline with a pack already on the phone (private, and works
     * anywhere), otherwise online if allowed. Null when neither can do it now.
     */
    private suspend fun translate(text: String, source: String, settings: Settings): String? {
        val services = graph.platform.text
        // An answer still in the source's own letters, or the text unchanged, isn't a translation:
        // the offline model gave up, so online gets a try.
        fun translated(answer: String?) = answer?.takeIf {
            it.isNotBlank() && !it.equals(text, ignoreCase = true) && NameText.scriptLetters(it, source) == 0
        }
        val packs = runCatching { services.offlineLanguages() }.getOrDefault(emptySet())
        LabelReading.offlineSource(source, packs)?.let { from ->
            if (runCatching { services.isTranslationReady(from, settings.language) }.getOrDefault(false)) {
                translated(runCatching { services.translate(LabelReading.forOfflineTranslation(text, source), from, settings.language) }.getOrNull())
                    ?.let { return it }
            }
        }
        if (!settings.translateOnline) return null
        return graph.onlineTranslator.translate(text, source, settings.language) { runCatching { services.identifyLanguage(it) }.getOrNull() }
    }

    /** The language of a name read from a tag, or null when it should stay as printed. */
    private suspend fun sourceLanguage(name: String, settings: Settings): String? {
        val label = settings.labelLanguage
        val identified = runCatching { graph.platform.text.identifyLanguage(name) }.getOrNull()
        if (label != null && NameText.isInScript(name, label)) {
            // Japanese names in kanji alone read as Chinese to language ID; the labels here decide.
            if (Languages.base(label) in setOf("ja", "zh", "ko")) return label
            // Language ID tells Russian from Ukrainian; for Georgian letters any other answer is a misfire.
            return identified?.takeIf { NameText.isInScript(name, it) } ?: label
        }
        // Latin text on a tag in another script is a brand or a loanword: keep it as printed.
        if (LabelReading.hasOwnScript(label)) return null
        // Latin letters: language ID can't be trusted with a few words (Spanish "Oferta" reads as
        // Portuguese or Italian), so the labels' language decides. It's overruled only by the user's
        // own language (nothing to translate), or another language common on labels here (Swiss French),
        // or where the labels are in the user's language anyway (imported products).
        if (label == null || Languages.base(label) == settings.language) return identified ?: label
        if (identified != null && Languages.base(identified) == settings.language) return identified
        if (identified != null && Languages.isOtherLabelLanguage(settings.country, identified)) return identified
        return label
    }

    private var lastPendingRun = 0L

    /** Translates names scanned while that wasn't possible, e.g. after a pack download (Settings). */
    fun retryTranslations() {
        scope.launch { translatePending() }
    }

    /** Translates names saved without a translation, and shows the result if one is on the card. */
    private suspend fun translatePending() {
        val settings = graph.settings.value ?: return
        lastPendingRun = now()
        for (product in graph.shopping.untranslatedProducts(settings.language).take(30)) {
            val done = translateIfNeeded(product, settings)
            // Stop at the first that can't be done: the rest can't either (offline).
            if (done.translatedLang == null) break
            _card.value?.takeIf { it.productId == product.id }?.let { card ->
                update(card.id) { it.copy(name = done.displayName, translation = null, nameStatus = NameStatus.DONE) }
            }
        }
    }

    // --- Card actions -------------------------------------------------------------------------------

    fun cancel() {
        aiToken++
        aiJob?.cancel()
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
            // A tap on AI that hasn't come back yet: buy the reading it returns, not the one it replaced.
            if (aiJob?.isActive == true) withTimeoutOrNull(20_000) { aiJob?.join() }
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
        // Removing a deal-only reading: the price printed with it was for one item after all.
        val price = card.multiBuy?.takeIf { offer == null && !it.singlePriceShown }?.price?.let(::parseDecimal) ?: card.localPrice
        _card.value = card.copy(multiBuy = offer, localPrice = price)
        card.observationId?.let { id ->
            scope.launch { graph.shopping.updateObservation(id) { it.copy(multiBuyJson = offer?.toJson(), price = price.toPlain()) } }
        }
    }

    fun rename(name: String) {
        val card = _card.value ?: return
        val trimmed = name.trim()
        _card.value = card.copy(name = trimmed, nameStatus = NameStatus.DONE)
        if (trimmed.isNotEmpty()) rememberCorrection(card.deviceName ?: card.originalName, trimmed)
        card.productId?.let { id -> scope.launch { graph.shopping.renameProduct(id, trimmed) } }
    }

    /** Resolves a tag printed in another currency than the local one (design §6.1). */
    /** Switches the card between the tag's currency and the local one ([ScanCard.otherCurrency]). */
    fun chooseCurrency(currency: CurrencyCode) {
        val card = _card.value ?: return
        val settings = graph.settings.value ?: return
        if (currency == card.localCurrency) return
        enrichJob?.cancel()
        aiToken++
        aiJob?.cancel()
        // The sighting was saved in the other currency; it's replaced by one in this currency.
        card.observationId?.let { id -> scope.launch { graph.shopping.deleteObservation(id) } }
        val updated = card.copy(
            localCurrency = currency, otherCurrency = card.localCurrency, rate = null, rateLoading = true,
            observationId = null, indicator = null, previous = null, nameStatus = NameStatus.READING,
            aiBusy = false,
        )
        _card.value = updated
        enrichJob = scope.launch { enrich(updated.id, card.tag, settings, currency, live = false, knownName = card.originalName) }
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
    /**
     * The shutter, for when live scanning doesn't pick the tag up: a full-resolution photo of
     * what's on screen, read without the live scan's strictness (design §6).
     */
    fun capture() {
        val settings = graph.settings.value ?: return
        if (_capturing.value) return
        _capturing.value = true
        graph.platform.haptics.tick()
        scope.launch {
            try {
                val frame = graph.platform.camera.capture()
                val tag = frame?.let { parsePhoto(it, settings) }
                if (tag?.price == null) {
                    graph.messages.show(if (frame == null) "Couldn't take a photo" else "No price found. Try closer, or type it in.")
                    return@launch
                }
                stabilizer.reset()
                show(tag, settings)
            } finally {
                _capturing.value = false
            }
        }
    }

    fun scanPhoto() {
        val settings = graph.settings.value ?: return
        scope.launch {
            val bytes = graph.platform.files.open(listOf("image/*")) ?: return@launch
            val frame = graph.platform.camera.scanImage(bytes)
            val tag = frame?.let { parsePhoto(it, settings) }
            if (tag?.price == null) {
                graph.messages.show("No price found in that photo")
                return@launch
            }
            stabilizer.reset()
            show(tag, settings)
        }
    }

    /**
     * A photo's tag. Where labels need Tesseract, prices may be in digits the fast recognizer can't
     * see (Persian ۱۲۰, Burmese ၁၀၀): if it finds no price, Tesseract reads the whole photo.
     */
    private suspend fun parsePhoto(frame: OcrFrame, settings: Settings): ParsedTag {
        val tag = withContext(Dispatchers.Default) {
            PriceTagParser.parse(ocrMemory.apply(frame), settings.localCurrency, storePhrases = storePhrases.known)
        }
        if (tag.price != null || fastReadsLabels(settings)) return tag
        val reader = readerFor(settings) ?: return tag
        val whole = Box(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
        val lines = withTimeoutOrNull(20_000) {
            runCatching { graph.platform.camera.readLines(frame.id, whole, reader, textHeight = frame.height / 40f) }.getOrNull()
        } ?: return tag
        // Only numbers in the labels' own digits count here: plain digits are the fast recognizer's
        // to read, so a plain number only Tesseract saw is more likely a misread letter (a stylised
        // Thai sign read as "4").
        val ownDigits = lines.filter { line -> line.text.none { it in '0'..'9' } || line.text.any { it.isDigit() && it.code >= 0x80 } }
        return withContext(Dispatchers.Default) {
            val rescued = ocrMemory.apply(OcrFrame(ownDigits, frame.width, frame.height, frame.barcodes, frame.id))
            PriceTagParser.parse(rescued, settings.localCurrency, storePhrases = storePhrases.known)
        }
    }

    /**
     * The shopper tapped AI. Sends the label crop they are looking at, whether or not the on-device
     * reading looks finished, and replaces the card with the answer. Nothing is learned here.
     */
    fun askAi() {
        val start = _card.value ?: return
        if (start.aiBusy) return
        val settings = graph.settings.value ?: return
        val key = settings.aiApiKey?.takeIf { it.isNotBlank() }
        if (key == null) {
            graph.messages.show("Add an AI key in Settings, then tap AI again.")
            return
        }
        val endpoint = AiEndpoint.resolve(settings)
        if (endpoint == null) {
            graph.messages.show("Add an https address and a model name in Settings.")
            return
        }
        if (start.tag.frameId == 0L) {
            graph.messages.show("There's no label photo to send. This price was typed in.")
            return
        }
        val cardId = start.id
        val tag = start.tag
        val token = ++aiToken
        _card.value = start.copy(
            aiBusy = true,
            deviceName = start.deviceName ?: start.originalName ?: start.tag.name,
        )
        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                val shot = withTimeoutOrNull(8_000) { runCatching { cropForAi(tag, settings) }.getOrNull() }
                if (aiToken != token || _card.value?.id != cardId) return@launch
                if (shot == null) {
                    graph.messages.show("Couldn't photograph this label. The reading is unchanged.")
                    return@launch
                }
                val reading = withTimeoutOrNull(12_000) {
                    runCatching {
                        graph.tagInterpreter.read(
                            endpoint, key, shot.jpeg, settings.country,
                            tag.price?.currency?.code ?: settings.localCurrency.code, settings.labelLanguage,
                        )
                    }.getOrNull()
                }
                if (aiToken != token || _card.value?.id != cardId) return@launch
                if (reading == null || !applyAiReading(cardId, reading, shot.ocr)) {
                    graph.messages.show("No AI reading came back. The reading is unchanged.")
                    return@launch
                }
                // The result is on the card now. Saving it, and translating the name, can finish after.
                update(cardId) { it.copy(aiBusy = false) }
                withTimeoutOrNull(8_000) { enrichJob?.join() }
                if (aiToken != token || _card.value?.id != cardId) return@launch
                persistAiReading(cardId, settings)
            } finally {
                if (aiToken == token) update(cardId) { it.copy(aiBusy = false) }
            }
        }
    }

    /** The shopper marked the AI reading right. This is the only time that reading is remembered. */
    fun confirmAi() {
        val card = _card.value ?: return
        if (!card.aiReplaced || card.aiConfirmed || card.aiBusy) return
        _card.value = card.copy(aiConfirmed = true)
        val seen = card.deviceName
        val chrome = card.aiChrome
        val productId = card.productId
        val printed = card.originalName
        scope.launch {
            val edited = productId?.let { graph.shopping.product(it) }?.userEditedName
            val confirmed = edited ?: printed.orEmpty()
            if (confirmed.isNotBlank() || chrome.isNotEmpty()) rememberCorrection(seen, confirmed, chrome)
            graph.messages.show("Saved for the next scan")
        }
    }

    /** The label's own crop and the text read on it. A live frame has no photo, so a still is taken. */
    private suspend fun cropForAi(tag: ParsedTag, settings: Settings): LabelCrop? {
        val area = PriceTagParser.scriptArea(tag)
        val direct = area?.let { graph.platform.camera.jpegOf(tag.frameId, it) }
        if (direct != null) return LabelCrop(tag.lines.joinToString("\n") { it.text }, direct)
        val frame = graph.platform.camera.capture(stillOnly = false) ?: return null
        val parsed = withContext(Dispatchers.Default) {
            PriceTagParser.parse(ocrMemory.apply(frame), settings.localCurrency, storePhrases = storePhrases.known)
        }
        // The on-device price can be the wrong digits. Send the label anyway; the shopper asked.
        val cropArea = parsed.price?.let { PriceTagParser.scriptArea(parsed) }
            ?: Box(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
        val jpeg = graph.platform.camera.jpegOf(frame.id, cropArea) ?: return null
        val ocr = if (parsed.lines.isNotEmpty()) parsed.lines.joinToString("\n") { it.text }
            else tag.lines.joinToString("\n") { it.text }
        return LabelCrop(ocr, jpeg)
    }

    /**
     * Puts the AI reading on the card. A price the shopper already picked by hand stays.
     * Returns false when the answer had neither a name nor a price.
     */
    private fun applyAiReading(cardId: Long, reading: AiTagReading, ocr: String): Boolean {
        var applied = false
        update(cardId) { card ->
            val shelf = card.tag.price
            val priceUntouched = shelf == null || card.localPrice.compareTo(shelf.amount) == 0
            val decimals = Currencies[card.localCurrency].decimals
            var price = card.localPrice
            var regular = card.regularPrice
            var promo = card.isPromo
            var deal = card.multiBuy
            val amount = money(reading.amount)?.takeIf { it.signum() > 0 }
            val was = money(reading.was)?.takeIf { it.signum() > 0 }
            val qty = reading.quantity
            val printed = reading.name?.trim()?.takeIf { it.length >= 2 }
            if (priceUntouched && amount != null && qty != null && qty in 2..12 && reading.amountIs.equals("total", ignoreCase = true)) {
                val offer = MultiBuyOffer.dealOnly(qty, amount, each = false)
                deal = offer
                price = offer.unitStandIn(decimals)
                if (was != null && was.compareTo(price) > 0) {
                    regular = was
                    promo = true
                } else {
                    regular = null
                    promo = false
                }
                applied = true
            } else if (priceUntouched && amount != null && qty != null && qty in 2..12 && reading.amountIs.equals("unit", ignoreCase = true)) {
                deal = MultiBuyOffer.each(qty, amount)
                price = amount
                if (was != null && was.compareTo(amount) > 0) {
                    regular = was
                    promo = true
                } else {
                    regular = null
                    promo = reading.promo
                }
                applied = true
            } else if (priceUntouched && amount != null) {
                deal = null
                price = amount
                if (was != null && was.compareTo(amount) > 0) {
                    regular = was
                    promo = true
                } else {
                    regular = null
                    promo = reading.promo
                }
                applied = true
            }
            if (printed != null) applied = true
            if (!applied) return@update card
            val name = printed ?: card.originalName
            val haystack = ocr + "\n" + card.tag.lines.joinToString("\n") { it.text }
            card.copy(
                localPrice = price,
                regularPrice = regular,
                isPromo = promo,
                multiBuy = deal,
                originalName = name,
                name = name,
                nameStatus = if (name == null) NameStatus.NONE else NameStatus.DONE,
                translation = null,
                translationFrom = null,
                deviceName = card.deviceName ?: card.originalName ?: card.tag.name,
                aiReplaced = true,
                aiConfirmed = false,
                aiChrome = reading.chrome.map { it.trim() }.filter { phrase ->
                    phrase.length >= 3 && haystack.contains(phrase, ignoreCase = true)
                },
            )
        }
        return _card.value?.takeIf { it.id == cardId }?.aiReplaced == true
    }

    /** Points the saved sighting at the AI price and printed name, then translates that name. */
    private suspend fun persistAiReading(cardId: Long, settings: Settings) {
        val card = _card.value?.takeIf { it.id == cardId && it.aiReplaced } ?: return
        val printed = card.originalName
        val saved = card.productId?.let { graph.shopping.product(it) }
        // Retitle only the product this scan just created from the on-device name. A product already
        // saved under a barcode, or one the shopper renamed, keeps the name it had.
        val device = card.deviceName ?: card.tag.name
        when {
            saved == null -> Unit
            saved.userEditedName != null -> update(cardId) { current ->
                if (!current.aiReplaced) current
                else current.copy(name = saved.displayName ?: current.name, nameStatus = NameStatus.DONE, translation = null)
            }
            !printed.isNullOrBlank() && (saved.originalName == null || saved.originalName == device || saved.originalName == printed) -> {
                var product = graph.shopping.adoptPrintedName(saved.id, printed) ?: saved
                product = translateIfNeeded(product, settings) { status, from ->
                    update(cardId) { current ->
                        if (!current.aiReplaced) current else current.copy(translation = status, translationFrom = from)
                    }
                }
                update(cardId) { current ->
                    if (!current.aiReplaced) current
                    else current.copy(
                        productId = product.id,
                        name = product.displayName ?: current.name,
                        nameStatus = if (product.displayName == null) NameStatus.NONE else NameStatus.DONE,
                        translation = if (product.translatedLang != null) null else current.translation,
                    )
                }
            }
        }
        val current = _card.value?.takeIf { it.id == cardId } ?: return
        val observationId = current.observationId ?: return
        graph.shopping.updateObservation(observationId) { obs ->
            obs.copy(
                productId = current.productId ?: obs.productId,
                price = current.localPrice.toPlain(),
                regularPrice = current.regularPrice?.toPlain(),
                isPromo = current.isPromo,
                promoSource = "AI",
                multiBuyJson = current.multiBuy?.toJson(),
            )
        }
        val observation = graph.shopping.observation(observationId) ?: return
        val previous = graph.shopping.previousSighting(observation)
        val indicator = run {
            val prevRate = previous?.observation?.fxRate?.let(::decimal) ?: return@run null
            val nowRate = observation.fxRate?.let(::decimal) ?: return@run null
            FxIndicator.compute(prevRate, nowRate)
        }
        update(cardId) { it.copy(indicator = indicator, previous = previous, productId = observation.productId) }
    }

    /** Remembers a checked name and any store chrome, and hands new words to Tesseract. */
    private fun rememberCorrection(seen: String?, confirmed: String, chrome: List<String> = emptyList()) {
        val wordsBefore = ocrMemory.userWords
        var changed = false
        if (confirmed.isNotBlank()) changed = ocrMemory.learn(seen, confirmed) || changed
        chrome.forEach { phrase ->
            if (ocrMemory.learnChrome(phrase)) {
                storePhrases.learn(phrase)
                changed = true
            }
        }
        if (changed) {
            val snapshot = ocrMemory.snapshot()
            scope.launch(Dispatchers.Default) { runCatching { graph.platform.camera.saveReadingNotes(snapshot) } }
        }
        val words = ocrMemory.userWords
        if (words != wordsBefore) scope.launch(Dispatchers.Default) { runCatching { graph.platform.camera.noteReaderWords(words) } }
    }

    private fun money(raw: String?): BigDecimal? {
        val cleaned = raw?.filter { it.isDigit() || it == '.' || it == ',' }?.replace(',', '.') ?: return null
        return parseDecimal(cleaned)
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
