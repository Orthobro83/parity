package app.parity.core.scan

import kotlin.concurrent.Volatile

/**
 * Lines printed on tag after tag are the store's, not a product's (design §6.2): a slogan in
 * words no vocabulary knows, or the store's name in its logo. A line seen on [minTags] tags with
 * different prices becomes one of [known], for this session. Only a tag's own lines are recorded
 * (within its outline), so a neighbouring product's name read beside several tags doesn't count.
 * Recorded from one thread; [known] can be read from any.
 */
class StorePhrases(private val minTags: Int = 3) {
    private val tagsByPhrase = mutableMapOf<String, MutableSet<String>>()

    @Volatile
    var known: Set<String> = emptySet()
        private set

    /** Notes the lines on the tag priced [priceKey] that could be names. */
    fun record(priceKey: String, lines: List<OcrLine>) {
        for (line in lines) {
            if (!line.readable || NameText.letterWeight(line.text) < 3) continue
            val phrase = phraseOf(line.text)
            if (phrase.isEmpty()) continue
            // Bounded: a long session forgets the oldest phrases seen once or twice.
            if (tagsByPhrase.size >= 500 && phrase !in tagsByPhrase) {
                tagsByPhrase.entries.firstOrNull { it.value.size < minTags }?.let { tagsByPhrase.remove(it.key) }
            }
            val tags = tagsByPhrase.getOrPut(phrase) { mutableSetOf() }
            tags += priceKey
            if (tags.size >= minTags && phrase !in known) known = known + phrase
        }
    }

    companion object {
        /** A line as compared: its words only, in lower case, without numbers or marks. */
        fun phraseOf(text: String): String =
            Names.normalize(NameText.withoutStripes(text)).split(' ').filter { word -> word.any { it.isLetter() } && word.none { it.isDigit() } }.joinToString(" ")
    }
}
