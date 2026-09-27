package app.parity.core.scan

import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.decimal
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

    fun parse(frame: OcrFrame, local: CurrencyCode?): ParsedTag {
        val localDecimals = local?.let { Currencies[it].decimals } ?: 2
        val candidates = mutableListOf<PriceCandidate>()
        frame.lines.forEach { line -> candidates += candidatesInLine(line, local, localDecimals) }
        mergeSuperscriptCents(frame, local, candidates)

        val barcode = frame.barcodes.firstOrNull()
        if (candidates.isEmpty()) {
            return ParsedTag.EMPTY.copy(barcode = barcode, promoSignals = emptyList())
        }

        val maxHeight = candidates.maxOf { it.box.height }.coerceAtLeast(1f)
        val scored = candidates.map { c ->
            var score = 0.55 * (c.box.height / maxHeight)
            if (c.currency != null) score += 0.25
            if (localDecimals > 0 && c.decimals == localDecimals) score += 0.10
            if (localDecimals == 0 && c.decimals == 0) score += 0.05
            if (c.isPerUnit) score -= 0.45
            c.copy(score = score)
        }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }

        val promo = PromoDetector.detect(frame.lines)
        val top = scored.firstOrNull()
        var best = top
        var regular: BigDecimal? = null
        if (top != null && promo.isPromo) {
            // On a promotion tag with two prices, the lower one is what you pay now.
            val pair = scored.firstOrNull { !it.isPerUnit && it.amount.compareTo(top.amount) != 0 && it.score >= top.score * 0.5 }
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
        val alternatives = if (chosen == null) emptyList() else scored
            .filter { it !== chosen && it.amount.compareTo(chosen.amount) != 0 && !it.isPerUnit && it.score >= chosen.score * 0.9 }
            .filter { regular == null || it.amount.compareTo(regular) != 0 }
            .distinctBy { it.amount.toStringExpanded() }
            .take(2)

        val name = NameFinder.find(frame, chosen)
        return ParsedTag(
            price = chosen,
            alternatives = alternatives,
            regularPrice = regular,
            isPromo = promo.isPromo,
            promoSignals = promo.signals,
            name = name?.first,
            nameBox = name?.second,
            nameRegion = chosen?.let { nameRegion(it, name?.second, frame) },
            barcode = barcode,
        )
    }

    private fun candidatesInLine(line: OcrLine, local: CurrencyCode?, localDecimals: Int): List<PriceCandidate> {
        val text = line.text
        val lower = text.lowercase()
        val perUnitLine = Lexicon.perUnitMarkers.any { lower.contains(it) }
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
            // "-20%" style discounts are signals, not prices.
            if (run.start > 0 && text[run.start - 1] in "-−–" && suffixLower.startsWith("%")) return@mapNotNull null

            val nearPerUnit = perUnitLine && (suffixLower.startsWith("/") || currency == null || lower.contains("/"))
            PriceCandidate(
                amount = run.amount,
                decimals = run.decimals,
                currency = currency,
                raw = run.raw,
                box = boxForRun(line, run),
                isPerUnit = perUnitLine && nearPerUnit,
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

    private fun nameRegion(price: PriceCandidate, nameBox: Box?, frame: OcrFrame): Box {
        val p = price.box
        val guess = Box(
            p.left - p.width * 2.5f, p.top - p.height * 3.5f,
            p.right + p.width * 1.5f, p.top,
        )
        val region = if (nameBox != null) {
            val pad = nameBox.height * 0.4f
            nameBox.copy(left = nameBox.left - pad, top = nameBox.top - pad, right = nameBox.right + pad, bottom = nameBox.bottom + pad)
        } else guess
        return region.clampTo(frame.width, frame.height)
    }
}
