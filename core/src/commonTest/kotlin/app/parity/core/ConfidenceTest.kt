package app.parity.core

import app.parity.core.money.CurrencyCode
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.NameText
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceTagParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int, confidence: Float? = null) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), confidence = confidence)

/** What the Latin recognizer made of a Georgian juice tag: the price, and gibberish for words. */
private fun firstPass() = PriceTagParser.parse(
    OcrFrame(listOf(l("gmfmombcmo F3o50", 150, 480, 390, 515), l("4.49 e", 190, 546, 405, 612), l("1 -ou gaun: 4.49 e", 145, 616, 250, 630)), 660, 1280),
    CurrencyCode.GEL,
)

/** Tesseract's confidence decides whether a reading may be a name (design §14). */
class NameConfidenceTest {
    @Test
    fun aConfidentReadingIsTheName() {
        val refined = PriceTagParser.refine(
            firstPass(), listOf(l("ფორთოხლის წვენი", 150, 480, 390, 515, 96f), l("4.49 «", 190, 546, 405, 612, 91f)), CurrencyCode.GEL, "ka",
        )
        assertEquals("ფორთოხლის წვენი", refined.name)
    }

    @Test
    fun anUnsureReadingIsNoName() {
        // As the emulator's low-resolution still gave it: two letters wrong, at 26.
        val refined = PriceTagParser.refine(
            firstPass(), listOf(l("კოითოალის წვენი", 150, 480, 390, 515, 26f), l("4.49 «", 190, 546, 405, 612, 68f)), CurrencyCode.GEL, "ka",
        )
        assertNull(refined.name)
        assertEquals("4.49", refined.price!!.amount.toPlain())
    }

    @Test
    fun anUnsureFragmentIsNoName() {
        // A cut-off reading of the name ("ილოგრა ("), and a confident line that's never a name.
        val refined = PriceTagParser.refine(
            firstPass(),
            listOf(l("ილოგრა (", 150, 480, 390, 515, 44f), l("4.49 «", 190, 546, 405, 612, 90f), l("1 ლ-ის ფასი: 4.49", 145, 616, 250, 630, 88f)),
            CurrencyCode.GEL, "ka",
        )
        assertNull(refined.name)
    }

    @Test
    fun theFocusedReadLeavesOutUnsureLines() {
        assertEquals("ფორთოხლის წვენი", PriceTagParser.pickName(listOf(l("ფორთოხლის წვენი", 0, 0, 100, 20, 96f)), "ka"))
        assertNull(PriceTagParser.pickName(listOf(l("ავლა წვენი", 0, 0, 100, 20, 58f)), "ka"))
        // Deal wording is left out whatever the confidence.
        assertEquals("ქოქოსის რძე", PriceTagParser.pickName(listOf(l("ქოქოსის რძე", 0, 0, 100, 20, 90f), l("შეიძინეთ 3 ცალი", 0, 30, 100, 50, 95f)), "ka"))
    }

    @Test
    fun linesWithoutAConfidenceAreTrusted() {
        // The fast recognizer gives none: its names are judged as before.
        assertTrue(l("Greek Yogurt", 0, 0, 10, 10).readable)
        assertTrue(l("Greek Yogurt", 0, 0, 10, 10, NameText.MIN_CONFIDENCE).readable)
        assertFalse(l("Greek Yogurt", 0, 0, 10, 10, NameText.MIN_CONFIDENCE - 1).readable)
    }
}

/** The price's own crop, read with digits only, confirms the fast recognizer's digits (design §6.1). */
class PriceCropTest {
    private val georgianView = listOf(l("ფორთოხლის წვენი", 150, 480, 390, 515, 96f), l("4.49 «", 190, 546, 405, 612, 91f))

    @Test
    fun aMatchingReadingConfirmsThePrice() {
        val refined = PriceTagParser.refine(firstPass(), georgianView, CurrencyCode.GEL, "ka", priceLines = listOf(l("4.49", 190, 546, 405, 612, 96f)))
        assertEquals("4.49", refined.price!!.amount.toPlain())
        assertTrue(refined.alternatives.isEmpty())
    }

    @Test
    fun aConfidentDifferentReadingIsOfferedNotTaken() {
        val refined = PriceTagParser.refine(firstPass(), georgianView, CurrencyCode.GEL, "ka", priceLines = listOf(l("4.99", 190, 546, 405, 612, 93f)))
        assertEquals("4.49", refined.price!!.amount.toPlain())
        assertEquals(listOf("4.99"), refined.alternatives.map { it.amount.toPlain() })
    }

    @Test
    fun anUnsureOrPartialReadingChangesNothing() {
        for (read in listOf(l("4.99", 190, 546, 405, 612, 41f), l("449", 190, 546, 405, 612, 95f))) {
            val refined = PriceTagParser.refine(firstPass(), georgianView, CurrencyCode.GEL, "ka", priceLines = listOf(read))
            assertTrue(refined.alternatives.isEmpty(), "$read")
        }
    }

    @Test
    fun theCropsCharacters() {
        val allowed = PriceTagParser.priceCharacters(CurrencyCode.GEL)
        // ASCII digits, separators, the lari and other signs, and the letters of GEL, USD and EUR.
        for (c in "0123456789.,'-₾€£$¥₽₩₺₴₪GELUSDR") assertTrue(c in allowed, "missing $c")
        // Not the label's letters, which would otherwise be read as digits there ("ც" as 6), nor
        // letters that look like digits, nor other digit systems: those are read on whole photos,
        // without this list.
        for (c in "ცლაoOlI၁٢۱") assertFalse(c in allowed, "unexpected $c")
        assertTrue('Y' !in allowed && 'J' !in allowed)
        assertTrue("JPY".all { it in PriceTagParser.priceCharacters(CurrencyCode("JPY")) })
    }
}
