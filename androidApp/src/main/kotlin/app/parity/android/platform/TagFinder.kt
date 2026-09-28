package app.parity.android.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import app.parity.core.scan.Box
import app.parity.core.scan.TagFinder
import app.parity.core.scan.TagQuad
import kotlin.math.hypot

/**
 * Price-tag outlines in camera pictures (design §6.1), found by the shared [TagFinder] on a small
 * grey copy: pure Kotlin, so no OpenCV and its native libraries for one contour search.
 */
internal object TagOutlines {
    /** Longest side of the copy searched: plenty for a tag's outline, and quick on a budget phone. */
    private const val SEARCH_SIDE = 480

    /** Outlines in [bitmap] once turned upright by [rotation] degrees, in upright pixels, best first. */
    fun find(bitmap: Bitmap, rotation: Int = 0): List<TagQuad> {
        val scale = minOf(1f, SEARCH_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }, true)
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val luma = ByteArray(w * h) { i ->
            val p = px[i]
            ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
        }
        return TagFinder.find(luma, w, h).map { it.scaled(1 / scale) }
    }

    /**
     * [quad] of [image] straightened into an upright rectangle, for reading only that tag: tags
     * are photographed at an angle. Parts outside the picture come out white. Null when too small.
     */
    fun straighten(image: Bitmap, quad: TagQuad, maxSide: Int): Straightened? {
        val (tl, tr, br, bl) = quad.corners
        val across = (hypot(tr.first - tl.first, tr.second - tl.second) + hypot(br.first - bl.first, br.second - bl.second)) / 2
        val down = (hypot(bl.first - tl.first, bl.second - tl.second) + hypot(br.first - tr.first, br.second - tr.second)) / 2
        val scale = minOf(1f, maxSide / maxOf(across, down))
        val w = (across * scale).toInt()
        val h = (down * scale).toInt()
        if (w < 8 || h < 8) return null
        val toStraight = Matrix()
        val src = floatArrayOf(tl.first, tl.second, tr.first, tr.second, br.first, br.second, bl.first, bl.second)
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        if (!toStraight.setPolyToPoly(src, 0, dst, 0, 4)) return null
        val toImage = Matrix()
        if (!toStraight.invert(toImage)) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(image, toStraight, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return Straightened(out, scale, toImage)
    }

    /** A straightened tag at [scale] to the picture, and the way back to it. */
    class Straightened(val bitmap: Bitmap, val scale: Float, private val toImage: Matrix) {
        /** [box] in the straightened tag, as a box in the picture. */
        fun toImage(box: Box): Box {
            val pts = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
            toImage.mapPoints(pts)
            val xs = floatArrayOf(pts[0], pts[2], pts[4], pts[6])
            val ys = floatArrayOf(pts[1], pts[3], pts[5], pts[7])
            return Box(xs.min(), ys.min(), xs.max(), ys.max())
        }
    }
}
