package app.parity.core.scan

/** One translation an online service offers for a product name. */
data class TranslationCandidate(
    val text: String,
    /** The source text the service matched, which may differ from what was asked. */
    val segment: String?,
    /** The service's own rating, 0–100. */
    val quality: Int,
    /** How closely [segment] matches the text asked, 0–1. */
    val match: Double,
    /** Machine translation of exactly the text asked, rather than a remembered human one. */
    val machine: Boolean,
)

/**
 * Chooses among an online translation service's answers (design §6.2). MyMemory's first answer
 * is sometimes a stray entry from its shared memory ("牛奶" → "Susu", the Malay word), so its machine
 * translation is used when there is one, and otherwise the remembered translations of exactly the
 * text asked that agree with each other.
 */
object TranslationPick {
    /** Usable translations of [source], best first. */
    fun rank(source: String, sourceLanguage: String, candidates: List<TranslationCandidate>): List<String> {
        val usable = candidates.map { it.copy(text = clean(it.text)) }.filter { usable(it.text, source, sourceLanguage) }
        usable.filter { it.machine }.map { it.text }.distinct().takeIf { it.isNotEmpty() }?.let { return it }

        val exact = usable.filter { it.segment != null && normalize(it.segment) == normalize(source) }
        val words = exact.map { words(it.text) }
        return exact.indices.sortedWith(
            compareByDescending<Int> { i ->
                // Answers that agree: the same text, or sharing a word ("Milk", "Cow's milk").
                exact.indices.count { j -> j != i && (normalize(exact[j].text) == normalize(exact[i].text) || words[i].any { it in words[j] }) }
            }.thenByDescending { exact[it].quality }.thenByDescending { exact[it].match },
        ).map { exact[it].text }.distinctBy(::normalize)
    }

    private fun usable(text: String, source: String, sourceLanguage: String): Boolean {
        if (text.isBlank() || normalize(text) == normalize(source)) return false
        // Untranslated, or a dictionary entry ("[にんじん] /carrot/Daucus carota/").
        if (NameText.scriptLetters(text, sourceLanguage) > 0) return false
        return text.none { it == '[' || it == ']' || it == '<' || it == '>' } && !text.trimStart().startsWith("/") && " /" !in text
    }

    private val entities = mapOf("&apos;" to "'", "&#39;" to "'", "&quot;" to "\"", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")

    private fun clean(text: String): String =
        entities.entries.fold(text) { acc, (entity, char) -> acc.replace(entity, char) }.replace(Regex("\\s+"), " ").trim()

    private fun normalize(text: String): String = Digits.normalize(text).lowercase().replace(Regex("\\s+"), " ").trim()

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }.toSet()
}
