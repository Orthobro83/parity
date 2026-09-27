package app.parity.core.scan

/** Picks the product name: the most name-like text near the price, preferring lines above it. */
internal object NameFinder {
    fun find(frame: OcrFrame, price: PriceCandidate?, exclude: Set<Int> = emptySet()): Pair<String, Box>? {
        val usable = frame.lines.withIndex().filter { (index, line) -> index !in exclude && isNameLike(line, price) }
        if (usable.isEmpty()) return null

        val scored = usable.map { (_, line) ->
            val letters = line.text.count { it.isLetter() }
            var score = minOf(letters, 30) / 30.0
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

        // Names often wrap onto a second line of the same size just below or above.
        val partner = usable.map { it.value }.filter { it !== bestLine }.firstOrNull { other ->
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

    private fun isNameLike(line: OcrLine, price: PriceCandidate?): Boolean {
        val text = line.text.trim()
        val letters = text.count { it.isLetter() }
        if (letters < 3) return false
        val nonSpace = text.count { !it.isWhitespace() }
        if (letters.toFloat() / nonSpace < 0.5f) return false
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.all { it in Lexicon.boilerplate || it in Lexicon.promoWords }) return false
        if (price != null && overlapsVertically(line.box, price.box) && line.box.horizontalOverlap(price.box) > 0f) return false
        return true
    }

    private fun overlapsVertically(a: Box, b: Box) = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0f
}
