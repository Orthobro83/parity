package app.parity.core

import app.parity.core.money.CurrencyCode
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.OcrElement
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceTagParser
import app.parity.core.scan.TagFinder
import app.parity.core.scan.TagQuad
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int, elements: List<OcrElement> = emptyList()) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), elements)

private fun e(text: String, left: Int, top: Int, right: Int, bottom: Int) =
    OcrElement(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))

private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
    TagQuad.of(listOf(left.toFloat() to top.toFloat(), right.toFloat() to top.toFloat(), right.toFloat() to bottom.toFloat(), left.toFloat() to bottom.toFloat()))

/** A greyscale test picture: shapes filled with grey levels, plus a little deterministic noise. */
private class Picture(val width: Int, val height: Int, background: Int) {
    val luma = ByteArray(width * height) { background.toByte() }

    fun fill(quad: TagQuad, level: Int) {
        val b = quad.bounds
        for (y in b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(height)) {
            for (x in b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(width)) {
                if (quad.contains(x + 0.5f, y + 0.5f)) luma[y * width + x] = level.toByte()
            }
        }
    }

    fun fill(left: Int, top: Int, right: Int, bottom: Int, level: Int) = fill(rect(left, top, right, bottom), level)

    fun noise(amplitude: Int) {
        var seed = 12345L
        for (i in luma.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val v = (luma[i].toInt() and 0xFF) + (seed % (2 * amplitude + 1)).toInt() - amplitude
            luma[i] = v.coerceIn(0, 255).toByte()
        }
    }
}

/** A rectangle [w] × [h] centred on ([cx], [cy]), turned by [degrees]: corners clockwise from the top left. */
private fun turned(cx: Float, cy: Float, w: Float, h: Float, degrees: Double): TagQuad {
    val a = degrees * kotlin.math.PI / 180
    val corners = listOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2).map { (x, y) ->
        (cx + x * cos(a) - y * sin(a)).toFloat() to (cy + x * sin(a) + y * cos(a)).toFloat()
    }
    return TagQuad.of(corners)
}

private fun assertNear(expected: TagQuad, actual: TagQuad, tolerance: Float) {
    expected.corners.zip(actual.corners).forEach { (e, a) ->
        assertTrue(abs(e.first - a.first) <= tolerance && abs(e.second - a.second) <= tolerance, "corner $a, expected near $e (all: ${actual.corners})")
    }
}

class TagFinderTest {
    /** A shelf rail across the middle, a tilted tag on it with print, and neighbours cut off by the frame. */
    private fun shelf(): Pair<Picture, TagQuad> {
        val p = Picture(270, 480, 45)
        p.fill(0, 150, 270, 330, 170) // rail
        p.fill(0, 150, 270, 157, 90) // lips
        p.fill(0, 323, 270, 330, 90)
        p.fill(-60, 180, 25, 300, 240) // neighbours, cut off
        p.fill(250, 180, 330, 300, 240)
        val tag = turned(135f, 240f, 170f, 110f, 5.0)
        p.fill(tag, 245)
        // Print: a name, big price digits, a barcode, laid out along the tag.
        fun onTag(x: Float, y: Float, w: Float, h: Float) = p.fill(turned(135f + x * cos(0.0873f) - y * sin(0.0873f), 240f + x * sin(0.0873f) + y * cos(0.0873f), w, h, 5.0), 25)
        onTag(-30f, -35f, 90f, 10f)
        for (i in 0 until 4) onTag(-45f + i * 26f, 5f, 18f, 36f)
        for (i in 0 until 8) onTag(30f + i * 5f, 38f, 2f, 16f)
        p.noise(3)
        return p to tag
    }

    @Test
    fun findsATiltedTagOnARail() {
        val (picture, tag) = shelf()
        val quads = TagFinder.find(picture.luma, picture.width, picture.height)
        val found = assertNotNull(quads.firstOrNull { it.contains(135f, 240f) && it.area < tag.area * 1.4f }, "quads: $quads")
        assertNear(tag, found, tolerance = 7f)
        // The neighbours are cut off by the frame, so they aren't taken for tags.
        assertTrue(quads.none { it.bounds.left < 20f && it.contains(10f, 240f) })
    }

    @Test
    fun aDealBandFullOfPrintIsPartOfTheTag() {
        val p = Picture(270, 480, 45)
        p.fill(0, 150, 270, 330, 170)
        p.fill(45, 180, 225, 255, 245) // white body
        p.fill(45, 255, 225, 300, 195) // yellow deal band
        p.fill(60, 195, 200, 205, 25) // name
        p.fill(90, 215, 190, 245, 25) // price
        p.fill(50, 259, 110, 297, 25) // deal wording and price, reaching the band's edges
        val quads = TagFinder.find(p.luma, p.width, p.height)
        val found = assertNotNull(quads.firstOrNull { it.contains(135f, 240f) && it.area < 180f * 120f * 1.4f }, "quads: $quads")
        assertNear(rect(45, 180, 225, 300), found, tolerance = 7f)
    }

    @Test
    fun nothingTagShapedInABlankView() {
        val p = Picture(270, 480, 128)
        p.noise(4)
        assertTrue(TagFinder.find(p.luma, p.width, p.height).isEmpty())
        assertTrue(TagFinder.find(ByteArray(10), 5, 2).isEmpty())
    }
}

class TagQuadTest {
    private val tag = rect(100, 100, 500, 300)

    @Test
    fun holdsBoxesMostlyOnIt() {
        assertTrue(tag.holds(Box(120f, 120f, 480f, 160f)))
        // Sticking out a little is still on the tag; mostly off it isn't.
        assertTrue(tag.holds(Box(420f, 120f, 530f, 160f)))
        assertFalse(tag.holds(Box(450f, 120f, 700f, 160f)))
        assertFalse(tag.holds(Box(0f, 0f, 0f, 0f)))
    }

    @Test
    fun reachAddsBandsAboveAndBelow() {
        val area = tag.reach()
        assertEquals(50f, area.bounds.top)
        assertEquals(350f, area.bounds.bottom)
        assertEquals(84f, area.bounds.left)
        assertEquals(516f, area.bounds.right)
        // Along a tilted tag's own sides.
        val tilted = turned(300f, 200f, 400f, 200f, 10.0).reach()
        assertTrue(tilted.contains(300f, 200f + 115f), "${tilted.corners}")
    }

    @Test
    fun scaling() {
        assertEquals(Box(50f, 50f, 250f, 150f), tag.scaled(0.5f).bounds)
        assertEquals(80000f, tag.area)
    }
}

/** Two tags side by side; the outline of the one aimed at keeps the other out (design §6.1). */
class NeighbouringTagTest {
    private val milk = listOf(l("Milk 1 L", 60, 300, 420, 350), l("\$2.49", 100, 380, 380, 480))
    // The neighbour's price is printed bigger, so without an outline it would win.
    private val bread = listOf(l("Bread Borodinsky 500 g", 560, 280, 940, 330), l("\$3.99", 600, 360, 900, 540))
    private val milkTag = rect(40, 270, 460, 510)

    private fun parse(lines: List<OcrLine>, quads: List<TagQuad>, strict: Boolean = false) =
        PriceTagParser.parse(OcrFrame(lines, 1000, 1000, tagQuads = quads), CurrencyCode.USD, strict)

    @Test
    fun theAimedTagsPriceAndNameWin() {
        assertEquals("3.99", parse(milk + bread, emptyList()).price!!.amount.toPlain())
        for (strict in listOf(false, true)) {
            val tag = parse(milk + bread, listOf(milkTag), strict)
            assertEquals("2.49", tag.price!!.amount.toPlain())
            assertEquals("Milk 1 L", tag.name)
            assertEquals(milkTag, tag.tagQuad)
            assertTrue(tag.lines.none { "Bread" in it.text || "3.99" in it.text })
            assertTrue(tag.alternatives.isEmpty())
        }
    }

    @Test
    fun aLineReadAcrossBothTagsIsCutAtTheEdge() {
        val across = l(
            "Milk 1 L Bread Borodinsky 500 g", 60, 300, 940, 350,
            listOf(e("Milk", 60, 300, 200, 350), e("1", 215, 300, 240, 350), e("L", 255, 300, 280, 350), e("Bread", 560, 300, 700, 350), e("Borodinsky", 710, 300, 880, 350), e("500", 890, 300, 920, 350), e("g", 925, 300, 940, 350)),
        )
        val tag = parse(listOf(across, l("\$2.49", 100, 380, 380, 480), l("\$3.99", 600, 360, 900, 540)), listOf(milkTag))
        assertEquals("Milk 1 L", tag.name)
        assertEquals("2.49", tag.price!!.amount.toPlain())
    }

    @Test
    fun anOutlineWithoutAPriceIsIgnored() {
        // A product's label, not a tag: the view is read as before.
        val label = rect(40, 20, 460, 200)
        val tag = parse(milk + bread + l("Fresh farm milk", 60, 60, 400, 100), listOf(label))
        assertEquals("3.99", tag.price!!.amount.toPlain())
        assertNull(tag.tagQuad)
    }

    @Test
    fun theTagInsideTheRailOrPhotoAroundIt() {
        // The finder ranks the rail around the tag first; the tag inside holds the price and its words.
        val rail = rect(0, 200, 1000, 760)
        val stray = l("Visible layers", 20, 700, 300, 740)
        val tag = parse(milk + stray, listOf(rail, milkTag))
        assertEquals(milkTag, tag.tagQuad)
        assertTrue(tag.lines.none { it === stray })
        assertEquals("Milk 1 L", tag.name)
    }

    @Test
    fun theSmallestOutlineWithTheNameIsTheTag() {
        // As the emulator's camera found a tag on a rail: the shelf around it, the rail band, the
        // tag, and the price's panel on the tag, best first.
        val shelf = rect(0, 200, 1000, 800)
        val railBand = rect(10, 220, 990, 560)
        val panel = rect(90, 370, 390, 490)
        val tag = parse(milk + l("per litre \$2.49", 60, 490, 400, 505), listOf(shelf, railBand, milkTag, panel))
        assertEquals(milkTag, tag.tagQuad)
        assertEquals("Milk 1 L", tag.name)
    }

    @Test
    fun withTheNameMissedTheTagIsTheOutlineWithWords() {
        // On a still of a Georgian tag the fast recognizer missed the name; what it made of the
        // price per litre is on the tag, not on the shelf around it.
        val shelf = rect(0, 200, 1000, 800)
        val tag = parse(listOf(l("\$2.49", 100, 380, 380, 480), l("1 o-ou aun: 4.49e", 60, 490, 400, 505)), listOf(shelf, milkTag))
        assertEquals(milkTag, tag.tagQuad)
        assertEquals("2.49", tag.price!!.amount.toPlain())
    }

    @Test
    fun aPricePanelGivesWayToTheTagAroundIt() {
        val panel = rect(90, 370, 390, 490)
        val tag = parse(milk + bread, listOf(panel, milkTag))
        assertEquals(milkTag, tag.tagQuad)
        assertEquals("Milk 1 L", tag.name)
        // With only the panel found, the name is looked for around it, as without an outline.
        val panelOnly = parse(milk, listOf(panel))
        assertEquals(panel, panelOnly.tagQuad)
        assertEquals("Milk 1 L", panelOnly.name)
        assertEquals("2.49", panelOnly.price!!.amount.toPlain())
    }

    @Test
    fun theScriptAreaIsTheTag() {
        val tag = parse(milk + bread, listOf(milkTag))
        assertEquals(milkTag.padded().bounds, PriceTagParser.scriptArea(tag))
        // The name area stays on the tag.
        val region = tag.nameRegion!!
        assertTrue(region.top >= milkTag.reach().bounds.top && region.right <= milkTag.reach().bounds.right, "region $region")
    }
}
