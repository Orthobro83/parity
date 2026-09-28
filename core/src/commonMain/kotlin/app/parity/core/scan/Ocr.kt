package app.parity.core.scan

/** Axis-aligned box in frame pixel coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: Box) = Box(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
    )

    fun clampTo(width: Int, height: Int) = Box(
        left.coerceIn(0f, width.toFloat()), top.coerceIn(0f, height.toFloat()),
        right.coerceIn(0f, width.toFloat()), bottom.coerceIn(0f, height.toFloat()),
    )

    fun horizontalOverlap(other: Box): Float = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersects(other: Box): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** One word-level piece of recognized text. */
data class OcrElement(val text: String, val box: Box)

/**
 * One line of recognized text with its words. [confidence] is how sure the recognizer is of it,
 * 0–100 on Tesseract's scale (ML Kit's is put on it by its adapter), where it says; readings below
 * [NameText.MIN_CONFIDENCE] aren't used as names.
 */
data class OcrLine(val text: String, val box: Box, val elements: List<OcrElement> = emptyList(), val confidence: Float? = null) {
    /** The part of this line on [area]: all of it, the words on it (a line read across two tags), or none. */
    fun within(area: TagQuad): OcrLine? {
        if (area.holds(box)) return this
        val kept = elements.filter { area.holds(it.box) }
        if (kept.isEmpty()) return null
        return OcrLine(kept.joinToString(" ") { it.text }, kept.map { it.box }.reduce(Box::union), kept, confidence)
    }

    /** Read well enough to be a name, when the recognizer says how well. */
    val readable: Boolean get() = (confidence ?: 100f) >= NameText.MIN_CONFIDENCE
}

/** Everything the recognizers found in one camera frame. */
data class OcrFrame(
    val lines: List<OcrLine>,
    val width: Int,
    val height: Int,
    val barcodes: List<String> = emptyList(),
    /** Identifies the image this frame came from, so a region can be re-read from that exact image. */
    val id: Long = 0,
    /** Outlines that may be price tags ([TagFinder]), best first; the parser picks the tag by its text. */
    val tagQuads: List<TagQuad> = emptyList(),
)

/**
 * A price tag's outline in an image (design §6.1): four corners in image pixels, clockwise from
 * the top left, so its text can be told from a neighbouring tag's and read straightened.
 */
data class TagQuad(
    val corners: List<Pair<Float, Float>>,
    val bounds: Box,
    val score: Float,
) {
    /** True when most of [box] is on the tag: at least 6 of 9 points spread over it. */
    fun holds(box: Box): Boolean {
        if (box.width <= 0f || box.height <= 0f) return false
        var inside = 0
        for (fy in FRACTIONS) for (fx in FRACTIONS) {
            if (contains(box.left + box.width * fx, box.top + box.height * fy)) inside++
        }
        return inside >= 6
    }

    /** True when ([x], [y]) is inside the outline. */
    fun contains(x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val (ax, ay) = corners[i]
            val (bx, by) = corners[(i + 1) % 4]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    /**
     * The tag with room around its outline, along its own sides: a quarter of its height above and
     * below for bands printed past the edge found (a sale banner, a deal strip), a little at the sides.
     */
    fun reach(vertical: Float = 0.25f, horizontal: Float = 0.04f): TagQuad {
        val (tl, tr, br, bl) = corners
        fun push(p: Pair<Float, Float>, from: Pair<Float, Float>, by: Float) =
            (p.first + (p.first - from.first) * by) to (p.second + (p.second - from.second) * by)
        // Up and down first, then outwards along the lengthened top and bottom.
        val t0 = push(tl, bl, vertical)
        val t1 = push(tr, br, vertical)
        val b1 = push(br, tr, vertical)
        val b0 = push(bl, tl, vertical)
        return of(listOf(push(t0, t1, horizontal), push(t1, t0, horizontal), push(b1, b0, horizontal), push(b0, b1, horizontal)), score)
    }

    /**
     * The tag with a thin margin, so print at its edges isn't cut, for reading it alone with a
     * script engine: the shelf that a wider margin takes in throws off its layout and contrast.
     */
    fun padded(): TagQuad = reach(vertical = 0.06f, horizontal = 0.03f)

    /** The same outline in an image scaled by [sx], [sy]. */
    fun scaled(sx: Float, sy: Float = sx): TagQuad = of(corners.map { (x, y) -> x * sx to y * sy }, score)

    /** Area in square pixels. */
    val area: Float
        get() {
            var twice = 0f
            for (i in 0 until 4) {
                val (ax, ay) = corners[i]
                val (bx, by) = corners[(i + 1) % 4]
                twice += ax * by - bx * ay
            }
            return kotlin.math.abs(twice) / 2f
        }

    companion object {
        private val FRACTIONS = floatArrayOf(1f / 6f, 0.5f, 5f / 6f)

        /** An outline from its [corners], clockwise from the top left. */
        fun of(corners: List<Pair<Float, Float>>, score: Float = 1f): TagQuad {
            require(corners.size == 4) { "a tag has 4 corners" }
            val bounds = Box(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
            return TagQuad(corners, bounds, score)
        }
    }
}
