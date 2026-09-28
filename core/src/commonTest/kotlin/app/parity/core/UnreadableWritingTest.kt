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

private val THB = CurrencyCode("THB")

/**
 * A Thai sign with no product name: "ราคา" (price) on a starburst, "88" in seven-segment digits and
 * "บาท" (baht), photographed on a monitor (a real report). Neither engine reads it well, and no
 * reading of it may become a name.
 */
class ThaiPriceSignTest {
    /** ML Kit on the shutter's photo (confidence on Parity's scale): the Thai word read as digits. */
    private val latinView = listOf(l("5101", 150, 330, 460, 420, 51f), l("88", 170, 460, 470, 800, 68f))

    private fun firstPass() = PriceTagParser.parse(OcrFrame(latinView, 600, 1000), THB)

    @Test
    fun junkWithAFewThaiLettersIsNoName() {
        // What Tesseract made of the sign on the phone, confidently.
        val refined = PriceTagParser.refine(
            firstPass(), listOf(l("A @s @a Ma ลห QR 0ป", 150, 330, 460, 420, 78f), l("88", 170, 460, 470, 800, 60f)), THB, "th",
        )
        assertNull(refined.name)
        assertEquals("88", refined.price!!.amount.toPlain())
        assertNull(NameText.pickLines("A @s @a Ma ลห QR 0ป", "th"))
        assertNull(PriceTagParser.pickName(listOf(l("A @s @a Ma ลห QR 0ป", 0, 0, 100, 20, 78f)), "th"))
    }

    @Test
    fun priceAndCurrencyWordsAreNoName() {
        // Read perfectly, the sign still has no name: "price" and "baht" aren't one.
        val refined = PriceTagParser.refine(
            firstPass(), listOf(l("ราคา", 150, 330, 460, 420, 95f), l("88", 170, 460, 470, 800, 90f), l("บาท", 200, 820, 440, 880, 94f)), THB, "th",
        )
        assertNull(refined.name)
        // A product line on such a sign is the name.
        val named = PriceTagParser.refine(
            firstPass(),
            listOf(l("นมสดพาสเจอร์ไรส์", 100, 200, 500, 280, 91f), l("ราคา", 150, 330, 460, 420, 95f), l("88", 170, 460, 470, 800, 90f), l("บาท", 200, 820, 440, 880, 94f)),
            THB, "th",
        )
        assertEquals("นมสดพาสเจอร์ไรส์", named.name)
    }

    @Test
    fun misreadPriceWordsAreStillPriceWords() {
        // What Tesseract made of the stylised "ราคา" on the emulator's photos, sure of some of them.
        for (misread in listOf("ฐาคา", "สาคา", "ศาคา", "ฮาคา")) {
            val refined = PriceTagParser.refine(firstPass(), listOf(l(misread, 150, 330, 460, 420, 90f), l("88", 170, 460, 470, 800, 90f)), THB, "th")
            assertNull(refined.name, misread)
            // Nor from the focused read of the name area.
            assertNull(PriceTagParser.pickName(listOf(l(misread, 0, 0, 100, 20, 90f)), "th"), misread)
        }
        assertNull(PriceTagParser.pickName("บาท", "th"))
    }

    @Test
    fun theScriptPassDecidesWhichNumberIsAWord() {
        // "ราคา" read as 5101 beside the 88: the script pass reads other writing on 5101's row, so
        // it was never a price, and the 88 is.
        val refined = PriceTagParser.refine(firstPass(), listOf(l("5107.", 150, 330, 460, 420, 56f)), THB, "th")
        assertEquals("88", refined.price!!.amount.toPlain())
        assertTrue(refined.candidates.none { it.amount.toPlain() == "5101" })
    }
}

/** The fast recognizer's readings of writing it can't read never become names (design §6.2). */
class FastRecognizerJunkTest {
    @Test
    fun anUnsureMlKitReadingIsNoName() {
        // ML Kit's reading of a Georgian name, with its confidence on Parity's scale (28 → 38).
        val unsure = PriceTagParser.parse(OcrFrame(listOf(l("gmfhonmbmou B3g50", 150, 480, 390, 515, 38f), l("\$4.49", 190, 546, 405, 612, 100f)), 660, 1280), CurrencyCode.USD)
        assertNull(unsure.name)
        val sure = PriceTagParser.parse(OcrFrame(listOf(l("Mandarina Naranja", 150, 480, 390, 515, 98f), l("\$4.49", 190, 546, 405, 612, 100f)), 660, 1280), CurrencyCode.USD)
        assertEquals("Mandarina Naranja", sure.name)
    }

    @Test
    fun aNameNeedsARealWord() {
        assertTrue(NameText.hasWord("Coca-Cola Zero 1.5 L"))
        assertTrue(NameText.hasWord("牛奶"))
        assertTrue(NameText.hasWord("ไข่ไก่ เบอร์ 2"))
        assertTrue(NameText.hasWord("मूल्य"))
        assertFalse(NameText.hasWord("A @s @a Ma QR 0ป"))
        assertFalse(NameText.hasWord("B8 5101"))
        assertNull(PriceTagParser.parse(OcrFrame(listOf(l("A @s @a Ma QR", 100, 100, 500, 150), l("\$4.49", 150, 200, 450, 320)), 1000, 1000), CurrencyCode.USD).name)
    }

    @Test
    fun aScriptNameHasAWordOfTheScript() {
        assertTrue(NameText.isScriptName("ราคา", "th"))
        assertTrue(NameText.isScriptName("ყავა Jacobs Monarch 95გ", "ka"))
        assertFalse(NameText.isScriptName("A @s @a Ma ลห QR 0ป", "th"))
        assertFalse(NameText.isScriptName("Jacobs", "ka"))
    }

    @Test
    fun priceWordsInOtherScriptsAreNoName() {
        // Words split at spaces and marks, not at vowel signs, so "मूल्य" (price) is one word.
        val hindi = PriceTagParser.parse(OcrFrame(listOf(l("मूल्य", 100, 100, 400, 160), l("₹ 49.00", 150, 200, 450, 320)), 1000, 1000), CurrencyCode("INR"))
        assertNull(hindi.name)
        val arabic = PriceTagParser.parse(OcrFrame(listOf(l("السعر", 100, 100, 400, 160), l("25.90 ر.س", 150, 200, 450, 320)), 1000, 1000), CurrencyCode("SAR"))
        assertNull(arabic.name)
        val russian = PriceTagParser.parse(OcrFrame(listOf(l("Цена", 100, 100, 400, 160), l("89,90 ₽", 150, 200, 450, 320), l("руб", 460, 280, 560, 320)), 1000, 1000), CurrencyCode("RUB"))
        assertNull(russian.name)
    }
}
