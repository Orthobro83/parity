package app.parity.core.scan

import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.decimal
import app.parity.core.money.toPlain
import com.ionspin.kotlin.bignum.decimal.BigDecimal

data class PriceCandidate(
    val amount: BigDecimal,
    val decimals: Int,
    /** Currency written next to the number, if any. */
    val currency: CurrencyCode?,
    val raw: String,
    val box: Box,
    val isPerUnit: Boolean,
    val score: Double = 0.0,
)

data class ParsedTag(
    /** The most likely shelf price, or null when nothing price-like was found. */
    val price: PriceCandidate?,
    /** Close competitors the user can pick instead (design §6.1: within 10 % of the best score). */
    val alternatives: List<PriceCandidate>,
    /** Pre-sale price when two prices are shown on a promotion tag. */
    val regularPrice: BigDecimal?,
    val isPromo: Boolean,
    val promoSignals: List<String>,
    /** Product name as read by the Latin OCR; may be unreadable for other scripts (see [nameRegion]). */
    val name: String?,
    val nameBox: Box?,
    /** Area likely to contain the product name, for re-reading with a script-specific OCR engine. */
    val nameRegion: Box?,
    val barcode: String?,
    /** [OcrFrame.id] of the frame this was read from. */
    val frameId: Long = 0,
    /** Multi-buy deal on the tag, if any; [price] stays the regular single-unit price. */
    val multiBuy: MultiBuyOffer? = null,
    /** Every scored price on the tag, kept for a second, script-aware pass ([PriceTagParser.refine]). */
    val candidates: List<PriceCandidate> = emptyList(),
    /** The fast recognizer's lines: gibberish for scripts it can't read, but in the right places. */
    val lines: List<OcrLine> = emptyList(),
    /** Outline of the tag the price was read on, when one was found (design §6.1). */
    val tagQuad: TagQuad? = null,
) {
    /** Currency printed on the tag, or null when the tag shows no currency (assume local). */
    val printedCurrency: CurrencyCode? get() = price?.currency

    companion object {
        val EMPTY = ParsedTag(null, emptyList(), null, false, emptyList(), null, null, null, null)
    }
}

/**
 * Turns the text found in one camera frame into a price, a name and sale signals (design §6.1–6.3).
 * Pure function of its input, so it is exhaustively unit-tested.
 */
object PriceTagParser {
    private const val MIN_SCORE = 0.2

    /**
     * With [strict] (continuous scanning), only a number written like a shelf price makes a tag,
     * so aiming at a page, a screen or a clock shows nothing; photos and forced captures aren't
     * strict ([looksLikeShelfPrice]). [storePhrases] are lines seen on tag after tag ([StorePhrases]).
     */
    fun parse(ocr: OcrFrame, local: CurrencyCode?, strict: Boolean = false, storePhrases: Set<String> = emptySet()): ParsedTag {
        val whole = ocr.withAsciiDigits()
        val localDecimals = local?.let { Currencies[it].decimals } ?: 2
        val everywhere = candidatesIn(whole, local, localDecimals)
        val aimed = aimedTag(whole, everywhere, localDecimals)
        val quad = aimed?.first
        // Only the aimed tag's own text, so a neighbouring tag's price, name and banners stay out.
        // An outline holding nothing but the price is a panel on the tag: words are looked for around it.
        val frame = if (quad != null && aimed.second) whole.copy(lines = whole.lines.mapNotNull { it.within(quad.reach()) }) else whole
        val candidates = (if (frame === whole) everywhere else candidatesIn(frame, local, localDecimals))
            .filter { quad == null || quad.holds(it.box) }

        val barcode = frame.barcodes.firstOrNull()
        if (candidates.isEmpty()) {
            return ParsedTag.EMPTY.copy(barcode = barcode, frameId = frame.id)
        }
        val scored = score(candidates, localDecimals)
        val (decided, dealLines) = decide(scored, frame.lines, localDecimals)
        if (strict && decided.price?.let { looksLikeShelfPrice(it, frame, localDecimals) } != true) {
            return ParsedTag.EMPTY.copy(barcode = barcode, frameId = frame.id)
        }
        val name = NameFinder.find(frame, decided.price, exclude = dealLines, storePhrases = storePhrases)
        return decided.copy(
            name = name?.first,
            nameBox = name?.second,
            nameRegion = decided.price?.let { nameRegion(it, name?.second, frame, quad) },
            barcode = barcode,
            frameId = frame.id,
            lines = frame.lines,
            tagQuad = quad,
        )
    }

    private fun candidatesIn(frame: OcrFrame, local: CurrencyCode?, localDecimals: Int): MutableList<PriceCandidate> {
        val candidates = mutableListOf<PriceCandidate>()
        frame.lines.forEach { line -> candidates += candidatesInLine(line, local, localDecimals) }
        mergeSuperscriptCents(frame, local, candidates)
        return candidates
    }

    /**
     * The outline of the tag aimed at (design §6.1), and whether it holds text besides the price.
     * The finder's best outline holding a price may be the rail or photo around the tag, or a panel
     * on it holding nothing but the price; so of it, the outlines inside it and those around it with
     * that price, the smallest with a name above the price (where names are) is the tag; failing
     * that, with a name anywhere, or any words: the fast recognizer can miss a line it can't read.
     * Null when no outline holds a price, as when none was found: then the whole view is read.
     */
    private fun aimedTag(frame: OcrFrame, candidates: List<PriceCandidate>, localDecimals: Int): Pair<TagQuad, Boolean>? {
        val prices = candidates.filter { looksLikePrice(it, localDecimals) }
        fun held(q: TagQuad) = prices.filter { q.holds(it.box) }
        val first = frame.tagQuads.firstOrNull { held(it).isNotEmpty() } ?: return null
        val price = held(first)
        // The biggest number is the price (a unit price under it is words on the tag).
        val main = price.maxBy { it.box.height }
        /** Lines on [q] besides the price's row that are a name above it (0), a name (1), or words (2). */
        fun has(q: TagQuad, tier: Int): Boolean = frame.lines.mapNotNull { it.within(q.reach()) }.any { line ->
            q.holds(line.box) && !sameRow(main.box, line.box) && when (tier) {
                0 -> NameFinder.isNameLike(line, null) && line.box.centerY < main.box.top
                1 -> NameFinder.isNameLike(line, null)
                else -> line.text.count { it.isLetter() } >= 2
            }
        }
        fun inside(inner: TagQuad, outer: TagQuad) = inner.area < outer.area * 0.8f && outer.contains(inner.bounds.centerX, inner.bounds.centerY)
        fun samePrice(q: TagQuad) = held(q).any { it in price }
        val within = listOf(first) + frame.tagQuads.filter { inside(it, first) && samePrice(it) }
        val around = frame.tagQuads.filter { inside(first, it) && samePrice(it) }
        for (tier in 0..2) {
            (within.filter { has(it, tier) }.minByOrNull { it.area } ?: around.filter { has(it, tier) }.minByOrNull { it.area })
                ?.let { return it to true }
        }
        return first to false
    }

    /**
     * The area around [tag]'s price to read with a script-aware engine: its tag when the outline
     * was found; otherwise a few lines above and below the price, widened
     * to take in whole lines of text there, as names often start well left of the price.
     */
    fun scriptArea(tag: ParsedTag): Box? {
        tag.tagQuad?.let { return it.padded().bounds }
        val p = tag.price?.box ?: return null
        if (p.width <= 0f || p.height <= 0f) return null // typed in: no image
        val base = Box(p.left - p.width * 1.2f, p.top - p.height * 2.5f, p.right + p.width * 1.2f, p.bottom + p.height * 3.2f)
        val margin = p.height * 0.3f
        return tag.lines.map { it.box }.filter { it.intersects(base) && it.height < p.height * 3 }
            .fold(base) { area, line -> area.union(Box(line.left - margin, line.top, line.right + margin, line.bottom)) }
    }

    /**
     * The product name in a script-aware engine's reading of a tag's name area, leaving out lines
     * of deal and sale wording ("შეიძინეთ 3 ცალი", "ფასდაკლება -20%"), which such a read also catches,
     * and lines it read too poorly to trust ([OcrLine.readable]).
     */
    fun pickName(lines: List<OcrLine>?, language: String?): String? =
        pickName(lines?.filter { it.readable }?.joinToString("\n") { it.text }, language)

    /** [pickName] of plain text, one line per line. */
    fun pickName(raw: String?, language: String?): String? {
        val kept = raw?.lines()?.filterNot { line ->
            val text = Lexicon.canonicalized(line)
            MultiBuyDetector.mentionsDeal(" " + text.lowercase() + " ") ||
                PromoDetector.detect(listOf(OcrLine(text, Box(0f, 0f, 0f, 0f)))).isPromo ||
                // Slogans, and "price", units or a currency alone ("ราคา" misread as "ฐาคา"), as on the whole tag.
                Advertising.isAdvertising(line) || Advertising.isUnitsOnly(line)
        }
        return NameText.pickLines(kept?.joinToString("\n"), language)
    }

    /**
     * The only characters the script engine may read in a price's own crop (design §14): ASCII
     * digits, decimal and grouping separators, currency signs, and the letters of the local
     * currency's code and of USD and EUR. There a label's letters can't be read as digits ("ც" as 6).
     * Never for names, wording or a whole photo, where Burmese or Arabic-Indic digits must be read.
     */
    fun priceCharacters(local: CurrencyCode?): String =
        ("0123456789.,'’-" + Lexicon.currencySigns + listOfNotNull(local?.code, "USD", "EUR").joinToString("")).toSet().joinToString("")

    /**
     * Second pass for scripts the fast recognizer can't read (design §14): [textLines] come from a
     * script-aware OCR of the same frame, so deal, sale and per-kilo wording ("3 ცალის ყიდვისას",
     * "ფასდაკლება", "1 კგ-ის ფასი") is understood, while prices still come from the fast recognizer.
     * [priceLines] are the engine's reading of the price's own crop with [priceCharacters] only.
     *
     * The name comes only from [textLines], preferring lines in [language]'s script: what the fast
     * recognizer made of the tag is gibberish, and its "name" may be stray Latin text elsewhere.
     */
    fun refine(
        tag: ParsedTag,
        scriptLines: List<OcrLine>,
        local: CurrencyCode? = null,
        language: String? = null,
        priceLines: List<OcrLine> = emptyList(),
        storePhrases: Set<String> = emptySet(),
    ): ParsedTag {
        val textLines = scriptLines.map { it.withAsciiDigits() }
        val unnamed = tag.copy(name = null, nameBox = null)
        if (tag.candidates.isEmpty() || textLines.isEmpty()) return unnamed
        val localDecimals = local?.let { Currencies[it].decimals } ?: 2
        val width = textLines.maxOf { it.box.right }.toInt().coerceAtLeast(1)
        val height = textLines.maxOf { it.box.bottom }.toInt().coerceAtLeast(1)
        val textFrame = OcrFrame(textLines, width, height)
        val script = NameText.scriptOf(language)

        val remarked = tag.candidates.filterNot { isPhantom(it, textLines) }.map { c ->
            val perUnit = !c.isPerUnit && textLines.any { line ->
                sameRow(c.box, line.box) && Lexicon.perUnitMarkers.any { Lexicon.canonicalized(line.text).lowercase().contains(it) }
            }
            if (perUnit) c.copy(isPerUnit = true, score = c.score - 0.45) else c
        }.sortedByDescending { it.score }
        if (remarked.none { it.score >= MIN_SCORE }) {
            val name = NameFinder.find(textFrame, tag.price, script = script, storePhrases = storePhrases)
            return unnamed.copy(name = name?.first, nameBox = name?.second)
        }
        val (decided, dealLines) = decide(remarked, textLines, localDecimals)
        // The name as the script-aware OCR reads it, skipping deal and promotion wording.
        val name = NameFinder.find(textFrame, decided.price, exclude = dealLines, script = script, storePhrases = storePhrases)
        return tag.copy(
            price = decided.price,
            alternatives = withCropReading(decided, tag.price, priceLines, localDecimals),
            regularPrice = decided.regularPrice,
            isPromo = decided.isPromo,
            promoSignals = decided.promoSignals,
            multiBuy = decided.multiBuy,
            candidates = remarked,
            name = name?.first,
            nameBox = name?.second,
        )
    }

    /**
     * The price's own crop read with digits only ([priceLines]) confirms its digits, or, when it
     * confidently reads another amount there, that amount is offered too (design §6.1): the fast
     * recognizer can misread a digit, and it still decides the price.
     */
    private fun withCropReading(decided: ParsedTag, read: PriceCandidate?, priceLines: List<OcrLine>, localDecimals: Int): List<PriceCandidate> {
        val price = decided.price ?: return decided.alternatives
        // Only for the price the crop was taken around, not a deal's price standing in for it.
        if (read == null || price.box != read.box || price.amount.compareTo(read.amount) != 0) return decided.alternatives
        val amounts = priceLines.filter { it.readable }.flatMap { AmountParser.findNumbers(Digits.normalize(it.text), localDecimals) }
            .filter { it.decimals == price.decimals && it.amount.signum() > 0 }
        if (amounts.isEmpty() || amounts.any { it.amount.compareTo(price.amount) == 0 }) return decided.alternatives
        val other = amounts.first()
        if (decided.regularPrice?.compareTo(other.amount) == 0) return decided.alternatives
        return (decided.alternatives + price.copy(amount = other.amount, raw = other.raw)).distinctBy { it.amount.toStringExpanded() }.take(2)
    }

    /**
     * A bare whole number the script-aware OCR doesn't see on its row: the fast recognizer made it
     * out of a letter it can't read (Georgian "ც" read as "6").
     */
    private fun isPhantom(c: PriceCandidate, textLines: List<OcrLine>): Boolean {
        if (c.decimals > 0 || c.currency != null) return false
        val digits = c.raw.filter { it.isAsciiDigit() }
        val row = textLines.filter { sameRow(c.box, it.box) && c.box.horizontalOverlap(it.box) > 0f }
        return row.isNotEmpty() && row.none { it.text.contains(digits) }
    }

    /**
     * A shelf price is written like one: with a currency sign, or with the local currency's
     * decimals (9.99, not 250 or 2026); it isn't a figure inside a sentence; and on a label with
     * several lines it stands out from the rest of the text, as prices do. Rulers, clocks, page
     * numbers, years and numbers in running text fail at least one of these.
     */
    private fun looksLikeShelfPrice(price: PriceCandidate, frame: OcrFrame, localDecimals: Int): Boolean {
        if (price.currency == null && price.decimals != localDecimals) return false
        // Where prices have no decimals (yen, won), a bare number could be anything (a year, a page):
        // it needs its currency mark or thousands grouping (2,980).
        if (localDecimals == 0 && price.currency == null && price.raw.none { it == ',' || it == '.' || it == ' ' || it == '\'' }) return false
        val line = frame.lines.firstOrNull { it.box.contains(price.box.centerX, price.box.centerY) }
        if (line != null && line.text.count { it.isLetter() } > line.text.count { it.isDigit() } * 2 + 2) return false
        val heights = frame.lines.map { it.box.height }.sorted()
        return heights.size < 4 || price.box.height >= heights[heights.size / 2] * 1.15f
    }

    /** Written like a price: with a currency sign or decimals, or in a currency without decimals. */
    private fun looksLikePrice(c: PriceCandidate, localDecimals: Int): Boolean =
        c.currency != null || c.decimals > 0 || localDecimals == 0

    /** Scores every number on the tag, best first; [decide] drops those too weak to be the price. */
    private fun score(candidates: List<PriceCandidate>, localDecimals: Int): List<PriceCandidate> {
        val maxHeight = candidates.maxOf { it.box.height }.coerceAtLeast(1f)
        return candidates.map { c ->
            var score = 0.55 * (c.box.height / maxHeight)
            if (c.currency != null) score += 0.25
            if (localDecimals > 0 && c.decimals == localDecimals) score += 0.10
            if (localDecimals == 0 && c.decimals == 0) score += 0.05
            if (c.isPerUnit) score -= 0.45
            c.copy(score = score)
        }.sortedByDescending { it.score }
    }

    /**
     * Chooses the regular price, a multi-buy deal and sale signals from scored candidates and the
     * tag's text. Also returns the indexes of the lines that hold the deal, so they aren't taken
     * for the product name.
     */
    private fun decide(all: List<PriceCandidate>, rawLines: List<OcrLine>, localDecimals: Int): Pair<ParsedTag, Set<Int>> {
        // Deal and sale words are matched with OCR near-misses corrected; indexes stay the same.
        val lines = rawLines.map { it.copy(text = Lexicon.canonicalized(it.text)) }
        // A deal's price may be printed small and bare ("2 ב-12"), so the detector sees every number;
        // the regular price has to stand out.
        val scored = all.filter { it.score >= MIN_SCORE }
        // A multi-buy deal's price must never be taken for the regular price, even when printed larger.
        val match = MultiBuyDetector.detect(lines, all, localDecimals)
        val dealLineBoxes = match?.lines?.mapNotNull { lines.getOrNull(it)?.box }.orEmpty()
        val withoutDeal = scored.filter { c ->
            c !== match?.candidate &&
                // The deal quantity itself ("3" in "3 ცალის ყიდვისას") is not a price.
                !(match != null && c.amount.compareTo(BigDecimal.fromInt(match.quantity)) == 0 && dealLineBoxes.any { sameRow(c.box, it) })
        }
        val regularGuess = withoutDeal.firstOrNull { !it.isPerUnit && looksLikePrice(it, localDecimals) }
        val dealPrice = match?.candidate
        // Only the deal is printed ("შეიძინეთ 3 ცალი ₾11.20-ად", "buy 3 for $10"): there's no single
        // price on the tag, so the deal's unit price stands in for it.
        val dealOnly = match != null && dealPrice != null && regularGuess == null && match.explicit
        val multiBuy = when {
            match == null -> null
            dealOnly -> MultiBuyOffer.dealOnly(match.quantity, dealPrice!!.amount, each = match.hint == MultiBuyOffer.Hint.EACH)
            regularGuess == null -> null
            else -> match.fixed ?: dealPrice?.let { MultiBuyOffer.resolve(match.quantity, it.amount, regularGuess.amount, match.hint) }
        }
        val unitStandIn = if (dealOnly && multiBuy != null) {
            val unit = multiBuy.unitStandIn(localDecimals)
            dealPrice!!.copy(amount = unit, decimals = localDecimals, raw = unit.toPlain())
        } else null
        val pool = when {
            unitStandIn != null -> listOf(unitStandIn) + withoutDeal
            multiBuy != null -> withoutDeal
            else -> scored
        }
        val promoAll = PromoDetector.detect(lines, if (multiBuy != null) match!!.lines else emptySet())
        // Deal wording alone doesn't reduce the single price, so it isn't a sale for price tracking.
        val promo = if (multiBuy != null) promoAll.copy(isPromo = promoAll.priceCut) else promoAll

        val top = pool.firstOrNull()
        var best = top
        var regular: BigDecimal? = null
        if (top != null && promo.isPromo) {
            // On a promotion tag with two prices, the lower one is what you pay now.
            // The old price is often printed small and without a currency sign, so the bar is low.
            // A bare "2" next to "2X$1.50" is the deal quantity, not a previous price. Old prices still
            // need no currency sign, but they are written as money (12.49), not as a one-digit count.
            val pair = pool.firstOrNull {
                !it.isPerUnit && it.amount.compareTo(top.amount) != 0 && it.score >= top.score * 0.25 &&
                    looksLikePrice(it, localDecimals)
            }
            if (pair != null) {
                val low = if (pair.amount < top.amount) pair else top
                val high = if (low === pair) top else pair
                if (high.amount <= low.amount * decimal(3)) {
                    best = low
                    regular = high.amount
                }
            }
        }

        val chosen = best
        // Other readings offered on the card must be written like prices too (not a ruler's 300).
        val alternatives = if (chosen == null) emptyList() else pool
            .filter { it !== chosen && it.amount.compareTo(chosen.amount) != 0 && !it.isPerUnit && it.score >= chosen.score * 0.9 }
            .filter { it.currency != null || it.decimals == localDecimals }
            .filter { regular == null || it.amount.compareTo(regular) != 0 }
            .distinctBy { it.amount.toStringExpanded() }
            .take(2)

        val tag = ParsedTag.EMPTY.copy(
            price = chosen,
            alternatives = alternatives,
            regularPrice = regular,
            isPromo = promo.isPromo,
            promoSignals = promo.signals,
            multiBuy = multiBuy,
            candidates = all,
        )
        return tag to (if (multiBuy != null) match!!.lines else emptySet())
    }

    private fun OcrLine.withAsciiDigits() =
        copy(text = Digits.normalize(text), elements = elements.map { it.copy(text = Digits.normalize(it.text)) })

    private fun OcrFrame.withAsciiDigits() = copy(lines = lines.map { it.withAsciiDigits() })

    /** Boxes on the same text row: they overlap vertically by at least half the smaller height. */
    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap > 0.5f * minOf(a.height, b.height)
    }

    private fun candidatesInLine(line: OcrLine, local: CurrencyCode?, localDecimals: Int): List<PriceCandidate> {
        val text = line.text
        val lower = text.lowercase()
        val perUnitLine = Lexicon.perUnitMarkers.any { lower.contains(it) }
        val secondaryLine = Lexicon.secondaryPriceMarkers.any { lower.contains(it) }
        return AmountParser.findNumbers(text, localDecimals).mapNotNull { run ->
            val digitsOnly = run.raw.all { it.isAsciiDigit() }
            if (digitsOnly && run.raw.length >= 7) return@mapNotNull null // barcode or article number
            if (isDateOrTime(text, run)) return@mapNotNull null
            if (run.amount.signum() <= 0) return@mapNotNull null

            val prefix = chunkBefore(text, run.start)
            val suffix = chunkAfter(text, run.end)
            var currency = Lexicon.currencyFor(prefix, local)
                ?: Lexicon.currencyFor(prefix.takeLast(1), local)
                ?: Lexicon.currencyFor(suffix, local)
                ?: Lexicon.currencyFor(suffix.take(1), local)
            val suffixLower = suffix.lowercase()
            if (currency == null && suffixLower.isNotEmpty()) {
                if (suffixLower == "ლ" && run.decimals == 2 && (local == null || local == CurrencyCode.GEL)) {
                    currency = CurrencyCode.GEL // "3.99 ლ" is lari; "1 ლ" / "0.5 ლ" is litres
                } else if (suffixLower in Lexicon.unitSuffixes || suffixLower.startsWith("%")) {
                    return@mapNotNull null
                }
            }
            // Deal quantities ("3 for", "buy 3", "3+", "1+1", "2/$5") are not prices.
            if (run.decimals == 0 && currency == null) {
                val prefixLower = prefix.lowercase()
                if (Lexicon.quantitySuffixes.any { suffixLower.startsWith(it) } || prefixLower in Lexicon.quantityPrefixes ||
                    prefixLower.endsWith("+")
                ) {
                    return@mapNotNull null
                }
            }
            // "-20%" style discounts are signals, not prices.
            if (run.start > 0 && text[run.start - 1] in "-−–" && suffixLower.startsWith("%")) return@mapNotNull null
            // "ニンジン (5)", "Eggs (10)": a count in brackets after a name.
            val before = text.getOrNull(run.start - 1)
            val after = text.getOrNull(run.end)
            if (run.decimals == 0 && currency == null && before != null && before in "(（" && after != null && after in ")）") {
                return@mapNotNull null
            }

            val nearPerUnit = perUnitLine && (suffixLower.startsWith("/") || currency == null || lower.contains("/"))
            PriceCandidate(
                amount = run.amount,
                decimals = run.decimals,
                currency = currency,
                raw = run.raw,
                box = boxForRun(line, run),
                isPerUnit = (perUnitLine && nearPerUnit) || secondaryLine,
            )
        }
    }

    /** Joins a large integer with small two-digit cents printed at its top right: "3⁹⁹" → 3.99. */
    private fun mergeSuperscriptCents(frame: OcrFrame, local: CurrencyCode?, candidates: MutableList<PriceCandidate>) {
        val elements = frame.lines.flatMap { line ->
            line.elements.ifEmpty { listOf(OcrElement(line.text, line.box)) }
        }
        for (big in elements) {
            val bigText = big.text.trim()
            if (bigText.length !in 1..4 || !bigText.all { it.isAsciiDigit() }) continue
            val h = big.box.height
            val cents = elements.firstOrNull { small ->
                val t = small.text.trim().takeWhile { it.isAsciiDigit() }
                small !== big && t.length == 2 &&
                    small.box.height in (h * 0.25f)..(h * 0.75f) &&
                    small.box.left >= big.box.right - h * 0.3f && small.box.left <= big.box.right + h * 0.8f &&
                    small.box.top >= big.box.top - h * 0.3f && small.box.centerY <= big.box.centerY
            } ?: continue
            val centsDigits = cents.text.trim().takeWhile { it.isAsciiDigit() }
            val trailing = cents.text.trim().drop(2).trim()
            val currency = Lexicon.currencyFor(trailing, local) ?: Lexicon.currencyFor(trailing.take(1), local)
            val merged = PriceCandidate(
                amount = decimal("$bigText.$centsDigits"),
                decimals = 2,
                currency = currency,
                raw = "$bigText.$centsDigits",
                box = big.box.union(cents.box).copy(top = big.box.top, bottom = big.box.bottom),
                isPerUnit = false,
            )
            // Drop the separate integer and cents readings of the same glyphs.
            candidates.removeAll { c -> overlaps(c.box, big.box) || overlaps(c.box, cents.box) }
            candidates += merged
        }
    }

    private fun overlaps(a: Box, b: Box): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return w > 0 && h > 0 && w * h >= 0.5f * minOf(a.width * a.height, b.width * b.height)
    }

    private fun isDateOrTime(text: String, run: NumberRun): Boolean {
        val before = text.getOrNull(run.start - 1)
        val after = text.getOrNull(run.end)
        val digitBefore = text.getOrNull(run.start - 2)?.isAsciiDigit() == true
        val digitAfter = text.getOrNull(run.end + 1)?.isAsciiDigit() == true
        return (before in listOf('/', ':') && digitBefore) || (after in listOf('/', ':') && digitAfter)
    }

    private fun chunkBefore(text: String, start: Int): String {
        var i = start - 1
        while (i >= 0 && text[i] == ' ') i--
        val end = i + 1
        while (i >= 0 && text[i] != ' ' && !text[i].isAsciiDigit()) i--
        return text.substring(i + 1, end)
    }

    private fun chunkAfter(text: String, end: Int): String {
        var i = end
        while (i < text.length && text[i] == ' ') i++
        val start = i
        while (i < text.length && text[i] != ' ' && !text[i].isAsciiDigit() && i - start < 8) i++
        return text.substring(start, i).trimEnd(',', ';', ':')
    }

    /** Estimates where a number sits inside its line using word boxes, or character position. */
    private fun boxForRun(line: OcrLine, run: NumberRun): Box {
        val digits = run.raw.filter { it.isAsciiDigit() }
        val matching = line.elements.filter { e -> e.text.filter { it.isAsciiDigit() }.let { it.isNotEmpty() && digits.contains(it) } }
        if (matching.isNotEmpty()) return matching.map { it.box }.reduce { a, b -> a.union(b) }
        val len = line.text.length.coerceAtLeast(1)
        val w = line.box.width
        return Box(
            line.box.left + w * run.start / len, line.box.top,
            line.box.left + w * run.end / len, line.box.bottom,
        )
    }

    private fun nameRegion(price: PriceCandidate, nameBox: Box?, frame: OcrFrame, quad: TagQuad?): Box {
        val region = nameRegion(price, nameBox, frame)
        // Not past the tag, into the shelf above.
        val tag = quad?.reach()?.bounds ?: return region
        return Box(maxOf(region.left, tag.left), maxOf(region.top, tag.top), minOf(region.right, tag.right), minOf(region.bottom, tag.bottom))
            .takeIf { it.width > 0f && it.height > 0f } ?: region
    }

    private fun nameRegion(price: PriceCandidate, nameBox: Box?, frame: OcrFrame): Box {
        val p = price.box
        val guess = Box(
            p.left - p.width * 2.5f, p.top - p.height * 3.5f,
            p.right + p.width * 1.5f, p.top,
        )
        // Where the fast recognizer can't read the script, its "name" may be stray Latin text
        // elsewhere in the frame; only trust its position when it sits on the tag, by the price.
        val column = Box(p.left - p.width, p.top, p.right + p.width, p.bottom)
        val onTag = nameBox != null && nameBox.horizontalOverlap(column) >= nameBox.width * 0.3f &&
            nameBox.bottom >= p.top - p.height * 3.5f && nameBox.top <= p.bottom + p.height * 3f
        val region = if (nameBox != null && onTag) {
            val pad = nameBox.height * 0.4f
            nameBox.copy(left = nameBox.left - pad, top = nameBox.top - pad, right = nameBox.right + pad, bottom = nameBox.bottom + pad)
        } else guess
        return region.clampTo(frame.width, frame.height)
    }
}
