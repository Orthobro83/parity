package app.parity.core.scan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Finds price tags in a greyscale image (design §6.1), so a tag is read on its own rather than
 * with its neighbours: a tag's paper makes a closed, four-sided outline against the shelf.
 *
 * Pure Kotlin on a small copy of the frame (about 480 px on the long side), so it runs on any
 * platform and needs no image library: OpenCV would add about 10 MB of native code per CPU type
 * for one contour search. Blur, gradient edges, then the regions those edges close off. Each
 * region of paper, or a stack of them (a sale banner, the body and a deal strip are bands of one
 * tag), seeds a shape: everything enclosed that connects to it within their bounds, print
 * included. A shape's convex hull is fitted with four corners and kept if it is tag-shaped.
 */
object TagFinder {
    /** Nearer than this the tag fills the view; further away its print is too small to read. */
    private const val MIN_AREA = 0.01f
    private const val MAX_AREA = 0.9f
    private const val MIN_ASPECT = 0.4f
    private const val MAX_ASPECT = 2.5f

    /**
     * Tag outlines in [luma] (one byte per pixel, row by row), best first: containing the centre,
     * where the shopper aims, then near it, large and rectangular.
     */
    fun find(luma: ByteArray, width: Int, height: Int, max: Int = 6): List<TagQuad> {
        if (width < 24 || height < 24 || luma.size < width * height) return emptyList()
        val size = width * height
        val edges = edgeMap(blurred(luma, width, height), width, height)
        val labels = IntArray(size)
        val regions = components(edges, labels, width, height)
        // Inside some closed outline: paper, print and the edges between them.
        val enclosed = BooleanArray(size) { edges[it] || !regions[labels[it] - 1].touchesBorder }
        val pieces = regions.filter { !it.touchesBorder && it.area >= size * MIN_AREA / 8 }
        val gap = maxOf(6, maxOf(width, height) / 48)
        val seeds = pieces.map { listOf(it) } + bands(pieces, gap)

        val stamp = IntArray(size)
        val stack = IntArray(size)
        val quads = seeds.mapIndexedNotNull { i, group ->
            shape(group, enclosed, stamp, i + 1, stack, width, height)?.let { quadOf(it, width, height) }
        }.sortedByDescending { it.score }
        // Near-duplicates go; nested outlines stay, since only the text on them tells a tag from the
        // price panel on it, or from the photo it's in ([PriceTagParser]).
        val kept = mutableListOf<TagQuad>()
        for (q in quads) {
            if (kept.none { sameOutline(it.bounds, q.bounds) }) kept += q
            if (kept.size == max) break
        }
        return kept
    }

    /** Two passes of a [1 2 1] blur each way: smooths sensor noise and a screen's moiré. */
    private fun blurred(luma: ByteArray, w: Int, h: Int): IntArray {
        val a = IntArray(w * h) { luma[it].toInt() and 0xFF }
        val b = IntArray(w * h)
        repeat(2) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    b[row + x] = (a[row + maxOf(x - 1, 0)] + 2 * a[row + x] + a[row + minOf(x + 1, w - 1)]) shr 2
                }
            }
            for (y in 0 until h) {
                val up = maxOf(y - 1, 0) * w
                val row = y * w
                val down = minOf(y + 1, h - 1) * w
                for (x in 0 until w) a[row + x] = (b[up + x] + 2 * b[row + x] + b[down + x]) shr 2
            }
        }
        return a
    }

    /**
     * Sobel edges with hysteresis, thickened by a pixel so small gaps in a tag's outline close.
     * The threshold follows the image within bounds: a white tag on a pale rail makes a faint edge
     * next to its print's strong ones, and a dim, noisy frame needs a higher one.
     */
    private fun edgeMap(gray: IntArray, w: Int, h: Int): BooleanArray {
        val mag = IntArray(w * h)
        val histogram = IntArray(2048)
        for (y in 1 until h - 1) {
            val up = (y - 1) * w
            val row = y * w
            val down = (y + 1) * w
            for (x in 1 until w - 1) {
                val gx = (gray[up + x + 1] + 2 * gray[row + x + 1] + gray[down + x + 1]) -
                    (gray[up + x - 1] + 2 * gray[row + x - 1] + gray[down + x - 1])
                val gy = (gray[down + x - 1] + 2 * gray[down + x] + gray[down + x + 1]) -
                    (gray[up + x - 1] + 2 * gray[up + x] + gray[up + x + 1])
                val m = minOf(abs(gx) + abs(gy), 2047)
                mag[row + x] = m
                histogram[m]++
            }
        }
        // A fraction of the strongest edges, and above the noise (most pixels are flat, so the
        // median gradient is noise).
        val total = (w - 2) * (h - 2)
        val high = maxOf(percentile(histogram, total, 0.99) / 8, percentile(histogram, total, 0.5) * 3).coerceIn(40, 96)
        val low = high / 2

        // Hysteresis: strong edges, plus weaker ones connected to them.
        val edge = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (i in mag.indices) {
            if (mag[i] < high || edge[i]) continue
            edge[i] = true
            var top = 0
            stack[top++] = i
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                for (ny in maxOf(py - 1, 0)..minOf(py + 1, h - 1)) for (nx in maxOf(px - 1, 0)..minOf(px + 1, w - 1)) {
                    val n = ny * w + nx
                    if (!edge[n] && mag[n] >= low) {
                        edge[n] = true
                        stack[top++] = n
                    }
                }
            }
        }
        // Thicken by one pixel.
        val wide = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) wide[row + x] = edge[row + x] || (x > 0 && edge[row + x - 1]) || (x < w - 1 && edge[row + x + 1])
        }
        val thick = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) thick[row + x] = wide[row + x] || (y > 0 && wide[row - w + x]) || (y < h - 1 && wide[row + w + x])
        }
        return thick
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        var seen = 0
        for (level in histogram.indices) {
            seen += histogram[level]
            if (seen >= total * fraction) return level
        }
        return histogram.size - 1
    }

    /** A region the edges close off. */
    private class Region(val label: Int, val start: Int) {
        var area = 0
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        var touchesBorder = false
    }

    /** Regions of non-edge pixels, 4-connected so a diagonal gap in an outline doesn't leak. */
    private fun components(edges: BooleanArray, labels: IntArray, w: Int, h: Int): List<Region> {
        val regions = mutableListOf<Region>()
        val stack = IntArray(w * h)
        for (start in edges.indices) {
            if (edges[start] || labels[start] != 0) continue
            val region = Region(regions.size + 1, start)
            regions += region
            labels[start] = region.label
            var top = 0
            stack[top++] = start
            while (top > 0) {
                val p = stack[--top]
                val x = p % w
                val y = p / w
                region.area++
                if (x < region.minX) region.minX = x
                if (x > region.maxX) region.maxX = x
                if (y < region.minY) region.minY = y
                if (y > region.maxY) region.maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) region.touchesBorder = true
                if (x > 0 && !edges[p - 1] && labels[p - 1] == 0) { labels[p - 1] = region.label; stack[top++] = p - 1 }
                if (x < w - 1 && !edges[p + 1] && labels[p + 1] == 0) { labels[p + 1] = region.label; stack[top++] = p + 1 }
                if (y > 0 && !edges[p - w] && labels[p - w] == 0) { labels[p - w] = region.label; stack[top++] = p - w }
                if (y < h - 1 && !edges[p + w] && labels[p + w] == 0) { labels[p + w] = region.label; stack[top++] = p + w }
            }
        }
        return regions
    }

    /**
     * Pieces that are bands of one tag: stacked with only a thin edge between them and mostly
     * overlapping (a sale banner over the body, a deal strip under it, paper between lines of
     * print), or side by side likewise. Each stack is one seed; side-by-side ones another.
     */
    private fun bands(pieces: List<Region>, gap: Int): List<List<Region>> {
        fun overlaps(a0: Int, a1: Int, b0: Int, b1: Int) = minOf(a1, b1) - maxOf(a0, b0) >= 0.5f * minOf(a1 - a0, b1 - b0)
        fun stacked(a: Region, b: Region) = (b.minY - a.maxY in 1..gap || a.minY - b.maxY in 1..gap) && overlaps(a.minX, a.maxX, b.minX, b.maxX)
        fun sideBySide(a: Region, b: Region) = (b.minX - a.maxX in 1..gap || a.minX - b.maxX in 1..gap) && overlaps(a.minY, a.maxY, b.minY, b.maxY)
        return listOf(::stacked, ::sideBySide).flatMap { joined ->
            val parent = IntArray(pieces.size) { it }
            fun root(i: Int): Int {
                var r = i
                while (parent[r] != r) r = parent[r]
                return r
            }
            for (i in pieces.indices) for (j in i + 1 until pieces.size) if (joined(pieces[i], pieces[j])) parent[root(i)] = root(j)
            pieces.indices.groupBy(::root).values.filter { it.size > 1 }.map { group -> group.map { pieces[it] } }
        }
    }

    /**
     * A shape's leftmost and rightmost pixel in each row from [top], and how much of it is the
     * seeds' own paper: most, for a tag; little for a ring round one (the rail between two tags).
     */
    private class Shape(val top: Int, val left: IntArray, val right: IntArray, val solidity: Float)

    /**
     * Everything enclosed that connects to [seeds] within their bounds (plus the outline's width):
     * the tag's paper with its print, which splits the paper into pieces.
     */
    private fun shape(seeds: List<Region>, enclosed: BooleanArray, stamp: IntArray, id: Int, stack: IntArray, w: Int, h: Int): Shape? {
        val x0 = maxOf(seeds.minOf { it.minX } - 3, 0)
        val x1 = minOf(seeds.maxOf { it.maxX } + 3, w - 1)
        val y0 = maxOf(seeds.minOf { it.minY } - 3, 0)
        val y1 = minOf(seeds.maxOf { it.maxY } + 3, h - 1)
        val left = IntArray(y1 - y0 + 1) { Int.MAX_VALUE }
        val right = IntArray(y1 - y0 + 1) { -1 }
        var area = 0
        var top = 0
        for (seed in seeds) {
            if (stamp[seed.start] == id) continue
            stamp[seed.start] = id
            stack[top++] = seed.start
        }
        while (top > 0) {
            val p = stack[--top]
            val x = p % w
            val y = p / w
            area++
            if (x < left[y - y0]) left[y - y0] = x
            if (x > right[y - y0]) right[y - y0] = x
            if (x > x0 && enclosed[p - 1] && stamp[p - 1] != id) { stamp[p - 1] = id; stack[top++] = p - 1 }
            if (x < x1 && enclosed[p + 1] && stamp[p + 1] != id) { stamp[p + 1] = id; stack[top++] = p + 1 }
            if (y > y0 && enclosed[p - w] && stamp[p - w] != id) { stamp[p - w] = id; stack[top++] = p - w }
            if (y < y1 && enclosed[p + w] && stamp[p + w] != id) { stamp[p + w] = id; stack[top++] = p + w }
        }
        return if (area > 0) Shape(y0, left, right, seeds.sumOf { it.area }.toFloat() / area) else null
    }

    /** The four-cornered outline of [shape], or null when it isn't tag-shaped. */
    private fun quadOf(shape: Shape, w: Int, h: Int): TagQuad? {
        val points = ArrayList<Pair<Float, Float>>(shape.left.size * 4)
        var filled = 0f
        for (i in shape.left.indices) {
            val l = shape.left[i]
            val r = shape.right[i]
            if (r < l) continue
            val y = (shape.top + i).toFloat()
            points += l.toFloat() to y
            points += (r + 1).toFloat() to y
            points += l.toFloat() to y + 1
            points += (r + 1).toFloat() to y + 1
            filled += (r - l + 1)
        }
        val hull = convexHull(points)
        if (hull.size < 4) return null
        val hullArea = polygonArea(hull)
        val quad = TagQuad.of(ordered(fourCorners(hull)))
        val area = quad.area
        val frame = w.toFloat() * h
        if (area < frame * MIN_AREA || area > frame * MAX_AREA) return null
        // Four corners describe it, and it fills them: not a round or ragged shape.
        if (area < hullArea * 0.9f || filled < area * 0.85f) return null
        val (tl, tr, br, bl) = quad.corners
        val across = (distance(tl, tr) + distance(bl, br)) / 2
        val down = (distance(tl, bl) + distance(tr, br)) / 2
        if (down <= 0f || across / down !in MIN_ASPECT..MAX_ASPECT) return null
        // Seen at an angle a tag's corners stay near square.
        if (quad.corners.indices.any { i -> angle(quad.corners, i) !in 50.0..130.0 }) return null

        val cx = w / 2f
        val cy = h / 2f
        val centreX = quad.corners.sumOf { it.first.toDouble() }.toFloat() / 4
        val centreY = quad.corners.sumOf { it.second.toDouble() }.toFloat() / 4
        val offCentre = sqrt((centreX - cx) * (centreX - cx) + (centreY - cy) * (centreY - cy)) / sqrt(cx * cx + cy * cy)
        val score = (if (quad.contains(cx, cy)) 0.3f else 0f) + 0.45f * (1f - offCentre) +
            0.35f * sqrt(area / frame) + 0.2f * (area / hullArea).coerceAtMost(1f) + 0.3f * shape.solidity.coerceAtMost(1f)
        return quad.copy(score = score)
    }

    /** Andrew's monotone chain, without collinear points. */
    private fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val sorted = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return sorted
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = mutableListOf<Pair<Float, Float>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = mutableListOf<Pair<Float, Float>>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** The hull cut down to four vertices, each time dropping the one whose triangle is smallest. */
    private fun fourCorners(hull: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val pts = hull.toMutableList()
        while (pts.size > 4) {
            var smallest = 0
            var smallestArea = Float.MAX_VALUE
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]
                val b = pts[i]
                val c = pts[(i + 1) % pts.size]
                val t = abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first))
                if (t < smallestArea) {
                    smallestArea = t
                    smallest = i
                }
            }
            pts.removeAt(smallest)
        }
        return pts
    }

    /** Clockwise on screen from the top left: sorted by angle around the centre. */
    private fun ordered(corners: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val cx = corners.sumOf { it.first.toDouble() } / 4
        val cy = corners.sumOf { it.second.toDouble() } / 4
        val clockwise = corners.sortedBy { (x, y) -> atan2(y - cy, x - cx) }
        val first = clockwise.indices.minBy { clockwise[it].first + clockwise[it].second }
        return List(4) { clockwise[(first + it) % 4] }
    }

    private fun polygonArea(p: List<Pair<Float, Float>>): Float {
        var twice = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            twice += a.first * b.second - b.first * a.second
        }
        return abs(twice) / 2f
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    /** Interior angle at corner [i], in degrees. */
    private fun angle(c: List<Pair<Float, Float>>, i: Int): Double {
        val p = c[i]
        val a = c[(i + 3) % 4]
        val b = c[(i + 1) % 4]
        val ux = (a.first - p.first).toDouble()
        val uy = (a.second - p.second).toDouble()
        val vx = (b.first - p.first).toDouble()
        val vy = (b.second - p.second).toDouble()
        val lengths = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        if (lengths == 0.0) return 0.0
        return acos(((ux * vx + uy * vy) / lengths).coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /** Boxes that are nearly the same: their overlap is most of their union. */
    private fun sameOutline(a: Box, b: Box): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return false
        val shared = w * h
        return shared > 0.85f * (a.width * a.height + b.width * b.height - shared)
    }
}
