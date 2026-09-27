package app.parity.core.scan

/**
 * Locks a price only after it reads the same in [requiredFrames] consecutive frames (design §6),
 * so the result card doesn't flicker while the camera moves.
 */
class PriceStabilizer(private val requiredFrames: Int = 3) {
    private val recent = ArrayDeque<ParsedTag>()
    var locked: ParsedTag? = null
        private set

    /** Feeds one frame. Returns the tag when a new price becomes stable, otherwise null. */
    fun offer(tag: ParsedTag): ParsedTag? {
        recent.addLast(tag)
        while (recent.size > requiredFrames) recent.removeFirst()
        if (recent.size < requiredFrames) return null

        val key = keyOf(tag) ?: return null
        if (recent.any { keyOf(it) != key }) return null
        val current = locked
        if (current != null && keyOf(current) == key) {
            // Same tag still in view: keep the best name reading without re-announcing.
            if (current.name == null && tag.name != null) locked = current.copy(name = tag.name, nameBox = tag.nameBox)
            return null
        }
        val withName = recent.lastOrNull { it.name != null } ?: tag
        val stable = tag.copy(
            name = withName.name,
            nameBox = withName.nameBox,
            isPromo = recent.any { it.isPromo },
            promoSignals = recent.flatMap { it.promoSignals }.distinct(),
            regularPrice = recent.firstNotNullOfOrNull { it.regularPrice },
            barcode = recent.firstNotNullOfOrNull { it.barcode },
        )
        locked = stable
        return stable
    }

    fun reset() {
        locked = null
        recent.clear()
    }

    private fun keyOf(tag: ParsedTag): String? =
        tag.price?.let { it.amount.toStringExpanded() + "|" + (it.currency?.code ?: "") }
}
