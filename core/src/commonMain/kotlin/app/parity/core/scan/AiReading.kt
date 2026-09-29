package app.parity.core.scan

/**
 * A structured reading of one label from a vision model. Nothing here is trusted until [accept]
 * checks it against the text the on-device recognizer actually saw.
 */
data class AiTagReading(
    val name: String? = null,
    val promo: Boolean = false,
    val quantity: Int? = null,
    /** Decimal text of the deal or unit amount, with a dot. */
    val amount: String? = null,
    /** "total" when [amount] pays for [quantity] units, otherwise "unit". */
    val amountIs: String? = null,
    val was: String? = null,
    val chrome: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
)

object AiReadingCheck {
    /**
     * The parts of [reading] that the OCR of the same crop supports. A price is kept only when its
     * digits were read. A name is kept when it shares a real word with the OCR, or when the
     * on-device name was unusable and an evidence quote was actually read. Null when nothing qualifies.
     */
    fun accept(reading: AiTagReading, ocrText: String, deviceName: String?): AiTagReading? {
        val ocr = ocrText.lowercase()
        val digits = ocr.filter { it.isDigit() }
        fun moneyOk(raw: String?) = raw?.filter { it.isDigit() }?.takeIf { it.length >= 2 && it in digits }
        val amount = moneyOk(reading.amount)
        val was = moneyOk(reading.was)
        val evidenceHit = reading.evidence.any { quote ->
            val q = quote.trim()
            q.length >= 4 && ocr.contains(q.lowercase())
        }
        val name = reading.name?.trim()?.takeIf { it.length >= 4 }?.takeIf { candidate ->
            sharesWord(candidate, ocrText) || (evidenceHit && !NameText.worthTranslating(deviceName ?: ""))
        }
        val chrome = reading.chrome.filter { it.length >= 3 && ocr.contains(it.lowercase()) }
        if (amount == null && name == null && chrome.isEmpty() && was == null) return null
        return reading.copy(name = name, amount = amount?.let { reading.amount }, was = was?.let { reading.was }, chrome = chrome)
    }

    /** True when the on-device reading is not something to leave as-is. A clean reading does not qualify. */
    fun needsHelp(tag: ParsedTag): Boolean {
        val text = tag.lines.joinToString(" ") { it.text }
        val timesMoney = Regex("(?i)(?<![\\d.,])\\d{1,2}\\s?[x×]\\s?[$€£¥₩₹₱₡₲₴₸₺₼₾₽฿]").containsMatchIn(text)
        // "2X$5.95" already read as a bundle, with no invented previous price, needs nothing more.
        if (timesMoney && (tag.multiBuy?.kind != MultiBuyOffer.Kind.BUNDLE || tag.regularPrice != null)) return true
        val name = tag.name?.trim().orEmpty()
        if (name.isEmpty() || Advertising.isChoppedBanner(name) || !NameText.worthTranslating(name)) return true
        val quantityLeftOut = tag.lines.any { line ->
            Advertising.hasQuantity(line.text) && !name.contains(line.text.trim(), ignoreCase = true)
        }
        return name.split(Regex("\\s+")).size < 3 && quantityLeftOut
    }

    private fun sharesWord(name: String, ocrText: String): Boolean {
        val ocrWords = ocrText.lowercase().split(Regex("[^\\p{L}\\p{M}]+")).filter { it.length >= 4 }.toSet()
        return name.lowercase().split(Regex("[^\\p{L}\\p{M}]+")).any { it.length >= 4 && it in ocrWords }
    }
}
