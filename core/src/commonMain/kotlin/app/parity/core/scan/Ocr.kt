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
}

/** One word-level piece of recognized text. */
data class OcrElement(val text: String, val box: Box)

/** One line of recognized text with its words. */
data class OcrLine(val text: String, val box: Box, val elements: List<OcrElement> = emptyList())

/** Everything the recognizers found in one camera frame. */
data class OcrFrame(
    val lines: List<OcrLine>,
    val width: Int,
    val height: Int,
    val barcodes: List<String> = emptyList(),
)
