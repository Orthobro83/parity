package app.parity.core.scan

import kotlin.math.abs

/**
 * Picks the product name: the most name-like text near the price, preferring lines above it and,
 * with a [script], lines written in it (a Georgian tag's name over stray Latin text nearby).
 * Letter case is never a reason to leave a line out: names are often printed in capitals.
 */
internal object NameFinder {
    /**
     * [storePhrases] are lines seen on tag after tag ([StorePhrases]): the store's slogans and name,
     * which lose to any other name on the tag but still name a tag that has nothing else.
     */
    fun find(
        frame: OcrFrame,
        price: PriceCandidate?,
        exclude: Set<Int> = emptySet(),
        script: ((Char) -> Boolean)? = null,
        storePhrases: Set<String> = emptySet(),
    ): Pair<String, Box>? {
        val usable = frame.lines.withIndex()
            .filter { (index, _) -> index !in exclude }
            .map { (index, line) -> IndexedValue(index, line.copy(text = NameText.withoutStripes(line.text))) }
            .filter { (_, line) -> isNameLike(line, price) }
        if (usable.isEmpty()) return null

        val scored = usable.map { (_, line) ->
            val letters = NameText.letterWeight(line.text)
            var score = minOf(letters, 30) / 30.0
            if (script != null && NameText.scriptRun(line.text, script) >= 3) score += 0.8
            // A name says what the product is and how much of it; marketing shouts.
            if (Advertising.hasQuantity(line.text)) score += 0.4
            score -= 0.6 * Advertising.share(line.text)
            if (line.text.any { it == '!' || it == '¡' }) score -= 0.2
            if (StorePhrases.phraseOf(line.text) in storePhrases) score -= 0.8
            if (price != null) {
                val p = price.box
                score += if (line.box.centerY < p.top) 0.6 else 0.15
                val distance = abs(line.box.centerY - p.centerY) / frame.height.coerceAtLeast(1)
                score -= distance * 1.2
                val widened = p.copy(left = p.left - p.width * 2, right = p.right + p.width * 2)
                if (line.box.horizontalOverlap(widened) > 0f) score += 0.2
                score += 0.3 * minOf(line.box.height / p.height.coerceAtLeast(1f), 1f)
            } else {
                score += 0.2 * (line.box.height / frame.height.coerceAtLeast(1))
            }
            line to score
        }.sortedByDescending { it.second }
        val candidates = scored.map { it.first }
        fun shouts(line: OcrLine) = isAllCaps(line.text) && looksLikeBanner(line, candidates, price)
        val bestLine = pick(scored, price, ::shouts)

        // Names often wrap onto a second line of the same size just below or above, in the same
        // script, and in capitals only if the name is.
        val bestInScript = script != null && NameText.scriptRun(bestLine.text, script) >= 3
        val bestShouts = shouts(bestLine)
        val partner = usable.map { it.value }.filter {
            it !== bestLine && (!bestInScript || NameText.scriptRun(it.text, script!!) >= 3) && (bestShouts || !shouts(it))
        }.firstOrNull { other ->
            val sameSize = other.box.height in (bestLine.box.height * 0.65f)..(bestLine.box.height * 1.35f)
            val gap = if (other.box.top >= bestLine.box.bottom) other.box.top - bestLine.box.bottom else bestLine.box.top - other.box.bottom
            sameSize && gap in (-bestLine.box.height * 0.2f)..(bestLine.box.height * 0.8f) &&
                other.box.horizontalOverlap(bestLine.box) > 0f &&
                (price == null || other.box.bottom <= price.box.top || other.box.top >= price.box.bottom) &&
                StorePhrases.phraseOf(other.text) !in storePhrases
        }
        val lines = listOfNotNull(bestLine, partner).sortedBy { it.box.top }
        val text = lines.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ")
        val box = lines.map { it.box }.reduce { a, b -> a.union(b) }
        return text to box
    }

    /**
     * The best-scored line; of two within a whisker, the one nearer the price, then the longer
     * (design §6.2). Capitals only decide against a line that also looks like a banner: names are
     * often printed in capitals too.
     */
    private fun pick(scored: List<Pair<OcrLine, Double>>, price: PriceCandidate?, shouts: (OcrLine) -> Boolean): OcrLine {
        val (first, firstScore) = scored[0]
        val second = scored.getOrNull(1)?.takeIf { firstScore - it.second < 0.1 }?.first ?: return first
        if (shouts(first) != shouts(second)) return if (shouts(first)) second else first
        if (price != null) {
            val d1 = abs(first.box.centerY - price.box.centerY)
            val d2 = abs(second.box.centerY - price.box.centerY)
            if (abs(d1 - d2) > minOf(first.box.height, second.box.height) / 2) return if (d1 < d2) first else second
        }
        return if (NameText.letterWeight(second.text) > NameText.letterWeight(first.text)) second else first
    }

    /** Letters with a case are all capitals ("CHICKEN BREAST"); scripts without case never are. */
    private fun isAllCaps(text: String): Boolean {
        val cased = text.filter { it.isUpperCase() || it.isLowerCase() }
        return cased.length >= 3 && cased.all { it.isUpperCase() }
    }

    /**
     * A banner's other marks: a marketing word, or a word or two alone in the strip atop the tag,
     * above another line that could be the name.
     */
    private fun looksLikeBanner(line: OcrLine, lines: List<OcrLine>, price: PriceCandidate?): Boolean {
        if (Advertising.share(line.text) > 0.0) return true
        val short = line.text.trim().split(Regex("\\s+")).size <= 2
        val overlap = line.box.height * 0.2f
        val atop = lines.all { it === line || it.box.top >= line.box.bottom - overlap }
        val nameBelow = lines.any { it !== line && it.box.top >= line.box.bottom - overlap && (price == null || it.box.bottom <= price.box.top) }
        return short && atop && nameBelow
    }

    /** "1 კგ-ის ფასი: 9.98", "per 100 g 1.25": the price per unit, not the product's name. */
    private val decimalNumber = Regex("\\d[.,]\\d{2}(?!\\d)")

    private fun isUnitPriceLine(text: String): Boolean {
        val lower = text.lowercase()
        return Lexicon.perUnitMarkers.any { lower.contains(it) } && decimalNumber.containsMatchIn(text)
    }

    /**
     * Whether a line may be a name. Only its words and place rule it out, never its letter case:
     * deal and sale wording or a slogan (the promotion vocabulary, a discount, a multi-buy), units,
     * prices or currencies alone, a price per unit, the price's own row, no real word at all, or a
     * reading the recognizer isn't sure of.
     */
    fun isNameLike(line: OcrLine, price: PriceCandidate?): Boolean {
        // A reading the script engine isn't sure of is no name: a fragment or a misread slogan.
        if (!line.readable) return false
        val text = line.text.trim()
        val letters = NameText.letterWeight(text)
        if (letters < 3) return false
        if (isUnitPriceLine(text)) return false
        val nonSpace = text.count { !it.isWhitespace() }
        if (letters.toFloat() / nonSpace < 0.5f) return false
        // A real word, not OCR junk made of a letter or two between marks ("A @s @a Ma QR").
        if (!NameText.hasWord(text)) return false
        // Deal banners and slogans ("Super Discount", "Celebra tus ahorros", "2 for 1") aren't names.
        if (Advertising.isAdvertising(text)) return false
        // What it's sold by, not what it is: "წონა 1 კგ", "each", "per kg".
        if (Advertising.isUnitsOnly(text)) return false
        if (price != null && sharesRow(line.box, price.box) && line.box.horizontalOverlap(price.box) > 0f) return false
        return true
    }

    /** On the price's row, not just touching it: Georgian letters reach well below the line. */
    private fun sharesRow(a: Box, b: Box) = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0.5f * minOf(a.height, b.height)
}
