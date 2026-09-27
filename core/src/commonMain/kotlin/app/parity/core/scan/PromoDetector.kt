package app.parity.core.scan

/** [priceCut] is true only for evidence the shown price itself is reduced (−20 %, "was 12.49"). */
data class PromoResult(val isPromo: Boolean, val signals: List<String>, val priceCut: Boolean = isPromo)

/** Sale detection from keywords, discount percentages and old-price labels (design §6.3). */
object PromoDetector {
    private val discountPercent = Regex("[-−–]\\s?\\d{1,2}(?:[.,]\\d)?\\s?%")
    private val percentOff = Regex("\\d{1,2}\\s?%\\s?(off|скидк|ფასდაკ|rabatt|de descuento|de réduction|indirim)")

    /** [skipLines] are lines that belong to a multi-buy deal, whose wording isn't a price cut. */
    fun detect(lines: List<OcrLine>, skipLines: Set<Int> = emptySet()): PromoResult {
        val signals = linkedSetOf<String>()
        var priceCut = false
        for ((index, line) in lines.withIndex()) {
            if (index in skipLines) continue
            val lower = line.text.lowercase()
            val words = lower.split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotEmpty() }
            words.filter { it in Lexicon.promoWords }.forEach { signals += it }
            if ("was" in words || "now" in words) signals += if ("was" in words) "was" else "now"
            Lexicon.promoPhrases.filter { lower.contains(it) }.forEach { signals += it.trim() }
            discountPercent.find(line.text)?.let { signals += it.value.replace(" ", ""); priceCut = true }
            percentOff.find(lower)?.let { signals += it.value; priceCut = true }
            Lexicon.oldPriceWords.firstOrNull { label ->
                if (label.all { it.isLetter() && it.code < 128 }) label in words else lower.contains(label)
            }?.let { signals += it; priceCut = true }
        }
        return PromoResult(signals.isNotEmpty(), signals.toList(), priceCut)
    }
}
