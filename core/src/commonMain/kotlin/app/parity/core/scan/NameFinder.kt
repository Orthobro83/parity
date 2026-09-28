package app.parity.core.scan

/**
 * Picks the product name: the most name-like text near the price, preferring lines above it and,
 * with a [script], lines written in it (a Georgian tag's name over stray Latin text nearby).
 */
internal object NameFinder {
    fun find(
        frame: OcrFrame,
        price: PriceCandidate?,
        exclude: Set<Int> = emptySet(),
        script: ((Char) -> Boolean)? = null,
    ): Pair<String, Box>? {
        val usable = frame.lines.withIndex()
            .filter { (index, _) -> index !in exclude }
            .map { (index, line) -> IndexedValue(index, line.copy(text = NameText.withoutStripes(line.text))) }
            .filter { (_, line) -> isNameLike(line, price) }
        if (usable.isEmpty()) return null

        val scored = usable.map { (_, line) ->
            val letters = NameText.letterWeight(line.text)
            var score = minOf(letters, 30) / 30.0
            if (script != null && line.text.count(script) >= 2) score += 0.8
            // A name says what the product is and how much of it; marketing shouts.
            if (Advertising.hasQuantity(line.text)) score += 0.4
            score -= 0.6 * Advertising.share(line.text)
            if (line.text.any { it == '!' || it == '¡' }) score -= 0.2
            if (price != null) {
                val p = price.box
                score += if (line.box.centerY < p.top) 0.6 else 0.15
                val distance = kotlin.math.abs(line.box.centerY - p.centerY) / frame.height.coerceAtLeast(1)
                score -= distance * 1.2
                val widened = p.copy(left = p.left - p.width * 2, right = p.right + p.width * 2)
                if (line.box.horizontalOverlap(widened) > 0f) score += 0.2
                score += 0.3 * minOf(line.box.height / p.height.coerceAtLeast(1f), 1f)
            } else {
                score += 0.2 * (line.box.height / frame.height.coerceAtLeast(1))
            }
            line to score
        }
        val bestLine = scored.maxBy { it.second }.first

        // Names often wrap onto a second line of the same size just below or above, in the same script.
        val bestInScript = script != null && bestLine.text.count(script) >= 2
        val partner = usable.map { it.value }.filter { it !== bestLine && (!bestInScript || it.text.count(script!!) >= 2) }.firstOrNull { other ->
            val sameSize = other.box.height in (bestLine.box.height * 0.65f)..(bestLine.box.height * 1.35f)
            val gap = if (other.box.top >= bestLine.box.bottom) other.box.top - bestLine.box.bottom else bestLine.box.top - other.box.bottom
            sameSize && gap in (-bestLine.box.height * 0.2f)..(bestLine.box.height * 0.8f) &&
                other.box.horizontalOverlap(bestLine.box) > 0f &&
                (price == null || other.box.bottom <= price.box.top || other.box.top >= price.box.bottom)
        }
        val lines = listOfNotNull(bestLine, partner).sortedBy { it.box.top }
        val text = lines.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ")
        val box = lines.map { it.box }.reduce { a, b -> a.union(b) }
        return text to box
    }

    /** "1 კგ-ის ფასი: 9.98", "per 100 g 1.25": the price per unit, not the product's name. */
    private val decimalNumber = Regex("\\d[.,]\\d{2}(?!\\d)")

    private fun isUnitPriceLine(text: String): Boolean {
        val lower = text.lowercase()
        return Lexicon.perUnitMarkers.any { lower.contains(it) } && decimalNumber.containsMatchIn(text)
    }

    private fun isNameLike(line: OcrLine, price: PriceCandidate?): Boolean {
        val text = line.text.trim()
        val letters = NameText.letterWeight(text)
        if (letters < 3) return false
        if (isUnitPriceLine(text)) return false
        val nonSpace = text.count { !it.isWhitespace() }
        if (letters.toFloat() / nonSpace < 0.5f) return false
        // Deal banners and slogans ("Super Discount", "Celebra tus ahorros") aren't names.
        if (Advertising.isAdvertising(text)) return false
        if (price != null && sharesRow(line.box, price.box) && line.box.horizontalOverlap(price.box) > 0f) return false
        return true
    }

    /** On the price's row, not just touching it: Georgian letters reach well below the line. */
    private fun sharesRow(a: Box, b: Box) = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0.5f * minOf(a.height, b.height)
}
