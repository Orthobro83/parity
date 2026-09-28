package app.parity.core.scan

/**
 * Chooses the product-name lines from raw OCR output (design §6.2). A crop around the name often
 * also catches part of the price, which Tesseract reads as short junk such as "AQ ar"; lines in the
 * label's own script win, and junk without a real word is dropped.
 */
object NameText {
    /**
     * Tesseract's confidence in a line (0–100) below which it isn't taken for a name (design §14):
     * the card asks for the name instead. On rendered Georgian shelf text 10–28 px tall, blurred and
     * noisy, 70 dropped 46 of 51 misreadings and 3 of 125 good readings; 65 kept twice the
     * misreadings, 75 lost twice the good ones.
     */
    const val MIN_CONFIDENCE = 70f

    private fun ranges(vararg bounds: Pair<Int, Int>): (Char) -> Boolean = { c -> bounds.any { (from, to) -> c.code in from..to } }

    private val georgian = ranges(0x10A0 to 0x10FF, 0x1C90 to 0x1CBF, 0x2D00 to 0x2D2F)
    private val cyrillic = ranges(0x0400 to 0x052F, 0x2DE0 to 0x2DFF, 0xA640 to 0xA69F)
    private val armenian = ranges(0x0530 to 0x058F, 0xFB13 to 0xFB17)
    private val greek = ranges(0x0370 to 0x03FF, 0x1F00 to 0x1FFF)
    private val hebrew = ranges(0x0590 to 0x05FF, 0xFB1D to 0xFB4F)
    private val arabic = ranges(0x0600 to 0x06FF, 0x0750 to 0x077F, 0x08A0 to 0x08FF, 0xFB50 to 0xFDFF, 0xFE70 to 0xFEFF)
    private val han = ranges(0x3400 to 0x4DBF, 0x4E00 to 0x9FFF, 0xF900 to 0xFAFF)
    private val kana = ranges(0x3040 to 0x30FF, 0x31F0 to 0x31FF, 0xFF66 to 0xFF9F)
    private val hangul = ranges(0x1100 to 0x11FF, 0x3130 to 0x318F, 0xAC00 to 0xD7AF)
    private val devanagari = ranges(0x0900 to 0x097F, 0xA8E0 to 0xA8FF)
    private val ethiopic = ranges(0x1200 to 0x139F, 0x2D80 to 0x2DDF, 0xAB00 to 0xAB2F)

    /** Each script-written language's letters; languages written in Latin letters aren't listed. */
    private val scripts: Map<String, (Char) -> Boolean> = buildMap {
        put("ka", georgian)
        listOf("ru", "uk", "be", "bg", "mk", "sr", "kk", "ky", "tg", "mn").forEach { put(it, cyrillic) }
        put("hy", armenian)
        put("el", greek)
        listOf("he", "yi").forEach { put(it, hebrew) }
        listOf("ar", "fa", "ur", "ps", "ug").forEach { put(it, arabic) }
        put("ja") { c -> han(c) || kana(c) }
        put("zh", han)
        put("ko") { c -> hangul(c) || han(c) }
        listOf("hi", "mr", "ne").forEach { put(it, devanagari) }
        listOf("am", "ti").forEach { put(it, ethiopic) }
        put("th", ranges(0x0E00 to 0x0E7F))
        put("lo", ranges(0x0E80 to 0x0EFF))
        put("km", ranges(0x1780 to 0x17FF, 0x19E0 to 0x19FF))
        put("my", ranges(0x1000 to 0x109F, 0xA9E0 to 0xA9FF, 0xAA60 to 0xAA7F))
        put("si", ranges(0x0D80 to 0x0DFF))
        put("bn", ranges(0x0980 to 0x09FF))
        put("ta", ranges(0x0B80 to 0x0BFF))
        put("te", ranges(0x0C00 to 0x0C7F))
        put("kn", ranges(0x0C80 to 0x0CFF))
        put("ml", ranges(0x0D00 to 0x0D7F))
        put("gu", ranges(0x0A80 to 0x0AFF))
        put("pa", ranges(0x0A00 to 0x0A7F))
        put("dv", ranges(0x0780 to 0x07BF))
    }

    /** Letters of [language]'s own script, or null for languages written in Latin letters. */
    fun scriptOf(language: String?): ((Char) -> Boolean)? = language?.let { scripts[it] }

    /** True when [text] has at least two letters in [language]'s own script. */
    fun isInScript(text: String?, language: String?): Boolean {
        val inScript = scriptOf(language) ?: return false
        return (text ?: return false).count(inScript) >= 2
    }

    /**
     * How much text [text] holds, in letters: a Chinese, Japanese or Korean character carries about
     * as much as two Latin letters ("牛奶" is milk).
     */
    fun letterWeight(text: String): Int = text.sumOf { c -> if (han(c) || kana(c) || hangul(c)) 2 else if (c.isLetter()) 1 else 0 }.toInt()

    /** Barcode stripes read as letters ("IIIIIIII", "lIl1lI") aren't words. */
    private val stripes = listOf(Regex("(?<![\\p{L}\\d])(\\p{L})\\1{3,}(?![\\p{L}\\d])"), Regex("(?<![\\p{L}\\d])[Il1|!ı]{4,}(?![\\p{L}\\d])"))

    fun withoutStripes(text: String): String =
        stripes.fold(text) { acc, stripe -> acc.replace(stripe, " ") }.replace(Regex("\\s+"), " ").trim()

    /** Letters of [text] in [language]'s own script. */
    fun scriptLetters(text: String, language: String?): Int = scriptOf(language)?.let { text.count(it) } ?: 0

    fun pickLines(raw: String?, language: String?): String? {
        val lines = raw?.lines()?.map { withoutStripes(it) }?.filter { it.isNotEmpty() } ?: return null
        val inScript = scriptOf(language)
        val chosen = if (inScript != null) {
            val scored = lines.map { line -> line to line.count(inScript) }
            val bestIndex = scored.indices.filter { scored[it].second >= 2 }.maxByOrNull { scored[it].second }
            if (bestIndex != null) {
                // A name wrapped onto a neighbouring line in the same script belongs to it; a line of
                // mostly other letters is OCR junk ("RRAA რრლ").
                fun wraps(line: Pair<String, Int>?) = line != null && line.second >= 3 && line.second * 2 >= line.first.count { it.isLetter() }
                listOfNotNull(
                    scored.getOrNull(bestIndex - 1)?.takeIf(::wraps)?.first,
                    scored[bestIndex].first,
                    scored.getOrNull(bestIndex + 1)?.takeIf(::wraps)?.first,
                ).take(2)
            } else {
                lines.filter(::looksLikeWords).take(1)
            }
        } else {
            lines.filter(::looksLikeWords).take(2)
        }
        return trimMarks(chosen.joinToString(" ")).takeIf { letterWeight(it) >= 3 }
    }

    /**
     * Stray marks OCR leaves around a reading ("| ვაშლი", "ილოგრა (", "იტა.", "ამლი,; 1"): marks at
     * either end, a bracket left open or closed at an end, and runs of punctuation.
     */
    fun trimMarks(text: String): String {
        var t = text.replace(Regex("([,;:!?|])[,;:!?|]+"), "$1").replace(Regex("\\s+"), " ").trim()
        repeat(2) {
            t = t.trimStart { !it.isLetterOrDigit() && it != '(' }.trimEnd { !it.isLetterOrDigit() && it != ')' && it != '%' }
            if (t.startsWith("(") && !t.contains(')')) t = t.drop(1)
            if (t.endsWith(")") && !t.contains('(')) t = t.dropLast(1)
        }
        return t.trim()
    }

    /** True when two readings of Latin text agree, allowing for a misread letter or two. */
    fun sameLatinText(a: String, b: String): Boolean {
        val x = Names.normalize(a)
        val y = Names.normalize(b)
        return x.length >= 3 && y.length >= 3 && Names.similarity(x, y) >= 0.85
    }

    /** At least 3 letters' worth, mostly letters, and one word of 3+ ([letterWeight]). */
    private fun looksLikeWords(line: String): Boolean {
        val letters = letterWeight(line)
        val nonSpace = line.count { !it.isWhitespace() }
        val longestWord = line.split(' ').maxOfOrNull(::letterWeight) ?: 0
        return letters >= 3 && letters * 2 >= nonSpace && longestWord >= 3
    }
}
