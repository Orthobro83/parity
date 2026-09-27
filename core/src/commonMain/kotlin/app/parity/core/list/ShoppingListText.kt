package app.parity.core.list

import app.parity.core.scan.Names

/** Splits free text such as "chicken, ground beef, coffee, and cheese" into list items (design §8.1). */
object ShoppingListText {
    private val separators = Regex("[,;\\n\\r•·]+|\\s+(?:and|&|\\+)\\s+", RegexOption.IGNORE_CASE)
    private val leadingNoise = Regex("^(?:and\\s+|&\\s+|[-*–—]\\s*|\\d+[.)]\\s+|\\[ ?\\]\\s*)", RegexOption.IGNORE_CASE)

    fun parse(input: String): List<String> {
        val seen = mutableSetOf<String>()
        return input.split(separators)
            .map { raw ->
                var item = raw.trim()
                while (true) {
                    val stripped = item.replace(leadingNoise, "").trim()
                    if (stripped == item) break
                    item = stripped
                }
                item.trimEnd('.', '!')
            }
            .filter { it.isNotEmpty() }
            .filter { seen.add(Names.tokens(it).joinToString(" ")) }
    }
}

/** Matches a bought product to a shopping-list item (design §8.3). */
object ListMatcher {
    const val THRESHOLD = 0.6

    /** 1.0 when every word of the item appears in the product name; fuzzy word similarity otherwise. */
    fun score(itemText: String, productName: String): Double {
        val item = Names.tokens(itemText)
        val product = Names.tokens(productName)
        if (item.isEmpty() || product.isEmpty()) return 0.0
        if (item.all { it in product }) return 1.0
        val perWord = item.map { word -> product.maxOf { Names.similarity(word, it) } }
        val average = perWord.average()
        return if (average >= 0.9) 0.8 * average else average * 0.5
    }

    fun <T> bestMatch(items: List<T>, text: (T) -> String, productNames: List<String>): T? {
        val names = productNames.filter { it.isNotBlank() }
        if (names.isEmpty()) return null
        return items
            .map { item -> item to names.maxOf { score(text(item), it) } }
            .filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }
}
