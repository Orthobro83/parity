package app.parity.core.scan

/** [priceCut] is true only for evidence the shown price itself is reduced (−20 %, "was 12.49"). */
data class PromoResult(val isPromo: Boolean, val signals: List<String>, val priceCut: Boolean = isPromo)

/** Sale detection from keywords, discount percentages and old-price labels (design §6.3). */
object PromoDetector {
    private val discountPercent = Regex("[-−–]\\s?\\d{1,2}(?:[.,]\\d)?\\s?%")
    private val percentOff = Regex(
        "\\d{1,2}\\s?%\\s?(off|скидк|ფასდაკ|rabatt|de descuento|de réduction|indirim|割引|オフ|引き|할인|ลด|تخفيض|خصم|הנחה|έκπτωση|знижк|попуст|popust|endirim|zniżk)",
    )

    /** Chinese discounts are written as the share paid: "8折" is 20 % off. */
    private val chineseDiscount = Regex("(?<![\\d.])[1-9](?:\\.\\d)?\\s?折")

    /** A discount on a line: "-20%", "20% off", "8折" (not a fat content such as "20%"). */
    fun hasDiscount(text: String): Boolean =
        discountPercent.containsMatchIn(text) || percentOff.containsMatchIn(text.lowercase()) || chineseDiscount.containsMatchIn(text)

    /** [skipLines] are lines that belong to a multi-buy deal, whose wording isn't a price cut. */
    fun detect(lines: List<OcrLine>, skipLines: Set<Int> = emptySet()): PromoResult {
        val signals = linkedSetOf<String>()
        var priceCut = false
        for ((index, line) in lines.withIndex()) {
            if (index in skipLines) continue
            val lower = line.text.lowercase()
            val words = lower.split(Regex("[^\\p{L}\\p{M}\\p{N}+]+")).filter { it.isNotEmpty() }
            words.filter { it in Lexicon.promoWords }.forEach { signals += it }
            if ("was" in words || "now" in words) signals += if ("was" in words) "was" else "now"
            Lexicon.promoPhrases.filter { lower.contains(it) }.forEach { signals += it.trim() }
            Lexicon.promoFragments.filter { lower.contains(it) }.forEach { signals += it }
            chineseDiscount.find(line.text)?.let { signals += it.value.replace(" ", ""); priceCut = true }
            discountPercent.find(line.text)?.let { signals += it.value.replace(" ", ""); priceCut = true }
            percentOff.find(lower)?.let { signals += it.value; priceCut = true }
            Lexicon.oldPriceWords.firstOrNull { label ->
                if (label.all { it.isLetter() && it.code < 128 }) label in words else lower.contains(label)
            }?.let { signals += it; priceCut = true }
        }
        return PromoResult(signals.isNotEmpty(), signals.toList(), priceCut)
    }
}
