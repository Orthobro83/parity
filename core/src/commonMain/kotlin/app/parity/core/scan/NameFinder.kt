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
                score += if (line.box.centerY < p.top) 0.35 else 0.25
                val distance = abs(line.box.centerY - p.centerY) / frame.height.coerceAtLeast(1)
                score -= distance * 1.2
                val widened = p.copy(left = p.left - p.width * 2, right = p.right + p.width * 2)
                if (line.box.horizontalOverlap(widened) > 0f) score += 0.2
                // A line taller than the price is a logo or a poster word, not the product.
                if (line.box.height > p.height * 1.15f) score -= 1.0
                else score += 0.3 * minOf(line.box.height / p.height.coerceAtLeast(1f), 1f)
            } else {
                score += 0.2 * (line.box.height / frame.height.coerceAtLeast(1))
            }
            line to score
        }.sortedByDescending { it.second }
        val candidates = scored.map { it.first }
        val byLine = scored.toMap()
        fun shouts(line: OcrLine) = isAllCaps(line.text) && looksLikeBanner(line, candidates, price)
        val clusters = clustersOf(candidates, price, script, storePhrases, ::shouts)
        val bestCluster = pickCluster(clusters, byLine, ::shouts, price) ?: return null
        val lines = bestCluster.sortedBy { it.box.top }
        val text = lines.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ")
        val box = lines.map { it.box }.reduce { a, b -> a.union(b) }
        return text to box
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
        // A date line ("DESDE: 02/09/2026") and a smashed package imprint are not the product.
        if (Regex("\\d{1,2}\\s?/\\s?\\d{1,2}\\s?/\\s?\\d{2,4}").containsMatchIn(text) && NameText.letterWeight(text) < 16) return false
        val only = text.trim().split(Regex("\\s+")).singleOrNull { it.any(Char::isLetter) }
        if (only != null && only.length >= 14 && only.any(Char::isLowerCase) && only.any(Char::isUpperCase)) return false
        // Deal banners and slogans ("Super Discount", "Celebra tus ahorros", "2 for 1") aren't names.
        if (Advertising.isAdvertising(text)) return false
        // What it's sold by, not what it is: "წონა 1 კგ", "each", "per kg".
        if (Advertising.isUnitsOnly(text)) return false
        if (price != null && sharesRow(line.box, price.box) && line.box.horizontalOverlap(price.box) > 0f) return false
        return true
    }

    /** On the price's row, not just touching it: Georgian letters reach well below the line. */
    private fun sharesRow(a: Box, b: Box) = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0.5f * minOf(a.height, b.height)

    /**
     * Wrapped name lines, up to four, on the same side of the price. A Selectos rail tag is three
     * lines under or beside the price; keeping only one partner dropped the brand and the size.
     */
    private fun clustersOf(
        lines: List<OcrLine>,
        price: PriceCandidate?,
        script: ((Char) -> Boolean)?,
        storePhrases: Set<String>,
        shouts: (OcrLine) -> Boolean,
    ): List<List<OcrLine>> {
        val parent = lines.indices.toMutableList()
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x]
            return x
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (adjacent(lines[i], lines[j], price) && canJoin(lines[i], lines[j], script, storePhrases, shouts)) union(i, j)
            }
        }
        return lines.indices.groupBy(::find).values.map { group ->
            group.map { lines[it] }.sortedBy { it.box.top }.take(4)
        }
    }

    /**
     * The best cluster. Within a whisker (the same bar the old line picker used), a banner in
     * capitals loses to the product, and otherwise the cluster nearer the price wins.
     */
    private fun pickCluster(
        clusters: List<List<OcrLine>>,
        scores: Map<OcrLine, Double>,
        shouts: (OcrLine) -> Boolean,
        price: PriceCandidate?,
    ): List<OcrLine>? {
        if (clusters.isEmpty()) return null
        val ranked = clusters.sortedByDescending { clusterScore(it, scores) }
        val first = ranked[0]
        val second = ranked.getOrNull(1) ?: return first
        if (clusterScore(first, scores) - clusterScore(second, scores) >= 0.1) return first
        if (first.all(shouts) && second.none(shouts)) return second
        if (second.all(shouts) && first.none(shouts)) return first
        if (price != null) {
            fun dist(lines: List<OcrLine>) = lines.minOf { abs(it.box.centerY - price.box.centerY) }
            val d1 = dist(first)
            val d2 = dist(second)
            val height = minOf(first.minOf { it.box.height }, second.minOf { it.box.height })
            if (abs(d1 - d2) > height / 2f) return if (d1 < d2) first else second
        }
        val w1 = first.sumOf { NameText.letterWeight(it.text) }
        val w2 = second.sumOf { NameText.letterWeight(it.text) }
        return if (w2 > w1) second else first
    }

    private fun clusterScore(lines: List<OcrLine>, scores: Map<OcrLine, Double>): Double {
        var score = lines.sumOf { scores[it] ?: 0.0 }
        if (lines.any { Advertising.hasQuantity(it.text) }) score += 0.35
        score += 0.12 * (lines.size - 1)
        return score
    }

    private fun canJoin(
        a: OcrLine,
        b: OcrLine,
        script: ((Char) -> Boolean)?,
        storePhrases: Set<String>,
        shouts: (OcrLine) -> Boolean,
    ): Boolean {
        if (StorePhrases.phraseOf(a.text) in storePhrases || StorePhrases.phraseOf(b.text) in storePhrases) return false
        if (shouts(a) != shouts(b)) return false
        if (script != null && NameText.scriptRun(a.text, script) >= 3 && NameText.scriptRun(b.text, script) < 3) return false
        if (script != null && NameText.scriptRun(b.text, script) >= 3 && NameText.scriptRun(a.text, script) < 3) return false
        val ratio = a.box.height / b.box.height.coerceAtLeast(1f)
        return ratio in 0.6f..1.7f
    }

    private fun adjacent(a: OcrLine, b: OcrLine, price: PriceCandidate?): Boolean {
        val gap = if (b.box.top >= a.box.bottom) b.box.top - a.box.bottom else a.box.top - b.box.bottom
        val height = minOf(a.box.height, b.box.height)
        val aSide = price?.let { verticalSide(a, it) } ?: 0
        val bSide = price?.let { verticalSide(b, it) } ?: 0
        // A poster prints the brand above the big price and the size under it. Those two lines
        // are one name when each one touches the price; a neighbouring tag further away does not.
        val acrossPrice = price != null && aSide != 0 && bSide != 0 && aSide != bSide &&
            touches(a, price) && touches(b, price) && gap <= price.box.height * 1.4f
        if (!acrossPrice && gap !in (-height * 0.25f)..(height * 0.85f)) return false
        if (a.box.horizontalOverlap(b.box) <= 0f) return false
        // A price printed beside the name spans those lines. Split only a line fully above the
        // price from one fully below it, and not when they are the two halves of the same name.
        if (!acrossPrice && aSide != 0 && bSide != 0 && aSide != bSide) return false
        return true
    }

    /** The line is directly above or below the price, not a tag further up the shelf. */
    private fun touches(line: OcrLine, price: PriceCandidate): Boolean {
        val gap = when {
            line.box.bottom <= price.box.top -> price.box.top - line.box.bottom
            line.box.top >= price.box.bottom -> line.box.top - price.box.bottom
            else -> 0f
        }
        return gap <= line.box.height * 0.85f
    }

    /** -1 above the price, 1 below it, 0 when the line overlaps the price (beside it, or on it). */
    private fun verticalSide(line: OcrLine, price: PriceCandidate): Int = when {
        line.box.bottom <= price.box.top -> -1
        line.box.top >= price.box.bottom -> 1
        else -> 0
    }
}
