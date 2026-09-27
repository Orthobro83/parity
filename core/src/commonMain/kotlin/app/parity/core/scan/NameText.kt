package app.parity.core.scan

/**
 * Chooses the product-name lines from raw OCR output (design §6.2). A crop around the name often
 * also catches part of the price, which Tesseract reads as short junk such as "AQ ar"; lines in the
 * label's own script win, and junk without a real word is dropped.
 */
object NameText {
    private val scripts: Map<String, (Char) -> Boolean> = mapOf(
        "ka" to { c -> c in 'Ⴀ'..'ჿ' || c in 'Ა'..'Ჿ' },
        "ru" to { c -> c in 'Ѐ'..'ӿ' },
        "uk" to { c -> c in 'Ѐ'..'ӿ' },
        "be" to { c -> c in 'Ѐ'..'ӿ' },
        "hy" to { c -> c in '԰'..'֏' },
        "el" to { c -> c in 'Ͱ'..'Ͽ' },
        "he" to { c -> c in '֐'..'׿' },
        "th" to { c -> c in '฀'..'๿' },
    )

    fun pickLines(raw: String?, language: String?): String? {
        val lines = raw?.lines()?.map { it.replace(Regex("\\s+"), " ").trim() }?.filter { it.isNotEmpty() } ?: return null
        val inScript = scripts[language]
        val chosen = if (inScript != null) {
            val scored = lines.map { line -> line to line.count(inScript) }
            val bestIndex = scored.indices.filter { scored[it].second >= 2 }.maxByOrNull { scored[it].second }
            if (bestIndex != null) {
                // A name wrapped onto a neighbouring line in the same script belongs to it.
                listOfNotNull(
                    scored.getOrNull(bestIndex - 1)?.takeIf { it.second >= 3 }?.first,
                    scored[bestIndex].first,
                    scored.getOrNull(bestIndex + 1)?.takeIf { it.second >= 3 }?.first,
                ).take(2)
            } else {
                lines.filter(::looksLikeWords).take(1)
            }
        } else {
            lines.filter(::looksLikeWords).take(2)
        }
        return chosen.joinToString(" ").trim().takeIf { it.length >= 3 }
    }

    /** At least 3 letters, mostly letters, and one word of 3+ letters. */
    private fun looksLikeWords(line: String): Boolean {
        val letters = line.count { it.isLetter() }
        val nonSpace = line.count { !it.isWhitespace() }
        val longestWord = line.split(' ').maxOfOrNull { word -> word.count { it.isLetter() } } ?: 0
        return letters >= 3 && letters * 2 >= nonSpace && longestWord >= 3
    }
}
