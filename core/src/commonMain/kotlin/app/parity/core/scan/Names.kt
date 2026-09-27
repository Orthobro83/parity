package app.parity.core.scan

/** Product-name normalization and fuzzy matching (design §6.4). */
object Names {
    private val unitJoin = Regex("(\\d)\\s+(g|gr|kg|ml|l|cl|oz|lb|გ|გრ|კგ|მლ|ლ|г|гр|кг|мл|л)\\b")

    /** Lower case, punctuation removed, whitespace collapsed, "500 g" → "500g". Keeps any script. */
    fun normalize(name: String): String {
        val lowered = name.lowercase()
            .map { if (it.isLetterOrDigit() || it == '.' || it == ',') it else ' ' }
            .joinToString("")
            .replace(Regex("(?<!\\d)[.,]|[.,](?!\\d)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return unitJoin.replace(lowered) { m -> m.groupValues[1] + m.groupValues[2] }
    }

    /** Jaro-Winkler similarity in 0..1. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val from = maxOf(0, i - window)
            val to = minOf(b.length - 1, i + window)
            for (j in from..to) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true; bMatched[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /** Tokens with a light English singularization, used to match list items to products. */
    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }.map(::singular)

    fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && (word.endsWith("oes") || word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
        else -> word
    }
}
