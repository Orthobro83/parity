package app.parity.core.scan

/**
 * What the phone has learned from checked readings. ML Kit's recognizer and Tesseract's models
 * cannot be retrained on the device. This remembers a misread line once the same correction has
 * been confirmed twice, and the words of confirmed names, which Tesseract can load as user words.
 * Recorded from the scan loop; [apply] can run on the camera thread.
 */
class OcrMemory {
    private val hits = mutableMapOf<String, Pair<String, Int>>()
    private val words = linkedSetOf<String>()
    private val chromePhrases = linkedSetOf<String>()

    @Volatile
    var replacements: Map<String, String> = emptyMap()
        private set

    val userWords: List<String> get() = words.toList()
    val chrome: Set<String> get() = chromePhrases.toSet()

    /** Replaces a line the phone has learned, before the tag is parsed. */
    fun apply(frame: OcrFrame): OcrFrame {
        if (replacements.isEmpty()) return frame
        var changed = false
        val lines = frame.lines.map { line ->
            val fixed = replacements[key(line.text)] ?: return@map line
            changed = true
            line.copy(text = fixed)
        }
        return if (changed) frame.copy(lines = lines) else frame
    }

    /**
     * [seen] is the on-device name, [confirmed] is a checked correction. The replacement is used
     * from the second time the same pair is confirmed. New words are available immediately.
     * Returns true when something new was stored.
     */
    fun learn(seen: String?, confirmed: String): Boolean {
        var changed = addWords(confirmed)
        val phrase = confirmed.trim()
        if (seen.isNullOrBlank() || key(seen) == key(phrase)) return changed
        val id = key(seen)
        val (previous, count) = hits[id] ?: (phrase to 0)
        if (previous != phrase) {
            hits[id] = phrase to 1
            return true
        }
        val next = count + 1
        hits[id] = phrase to next
        if (next >= 2 && replacements[id] != phrase) {
            replacements = replacements + (id to phrase)
            changed = true
        }
        return changed || next == 1
    }

    /** A line confirmed as store chrome rather than a product name. */
    fun learnChrome(text: String): Boolean {
        val phrase = StorePhrases.phraseOf(text)
        if (phrase.isEmpty() || phrase in chromePhrases) return false
        chromePhrases += phrase
        return true
    }

    fun snapshot(): String = buildString {
        chromePhrases.forEach { append("C\t").append(it.replace('\t', ' ')).append('\n') }
        words.forEach { append("W\t").append(it.replace('\t', ' ')).append('\n') }
        hits.forEach { (seen, pair) ->
            append("R\t").append(pair.second).append('\t').append(seen.replace('\t', ' '))
            append('\t').append(pair.first.replace('\n', ' ').replace('\t', ' ')).append('\n')
        }
    }

    fun restore(text: String) {
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "C" -> if (parts.size >= 2) chromePhrases += parts[1]
                "W" -> if (parts.size >= 2) words += parts[1]
                "R" -> if (parts.size >= 4) {
                    val count = parts[1].toIntOrNull() ?: return@forEach
                    hits[parts[2]] = parts[3] to count
                    if (count >= 2) replacements = replacements + (parts[2] to parts[3])
                }
            }
        }
    }

    private fun addWords(text: String): Boolean {
        var changed = false
        NameText.withoutStripes(text).split(Regex("[^\\p{L}\\p{M}'’-]+")).forEach { raw ->
            val word = raw.trim()
            if (word.length in 3..40 && word.any(Char::isLetter) && word !in words && words.size < 400) {
                words += word
                changed = true
            }
        }
        return changed
    }

    private fun key(text: String) = NameText.withoutStripes(text).lowercase().replace(Regex("\\s+"), " ").trim()
}
