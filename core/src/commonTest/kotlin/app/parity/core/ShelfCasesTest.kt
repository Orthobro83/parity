package app.parity.core

import app.parity.core.money.CurrencyCode
import app.parity.core.money.toFixed
import app.parity.core.scan.Advertising
import app.parity.core.scan.AiReadingCheck
import app.parity.core.scan.AiTagReading
import app.parity.core.scan.Box
import app.parity.core.scan.MultiBuyOffer
import app.parity.core.scan.NameText
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.OcrMemory
import app.parity.core.scan.PriceTagParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun shelf(vararg lines: OcrLine) =
    PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), CurrencyCode.USD)

private fun at(text: String, left: Int, top: Int, right: Int, bottom: Int) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))

/** Layouts from a Selectos shop in El Salvador: USD, Spanish, home currency irrelevant to parsing. */
class ShelfCasesTest {
    @Test
    fun twoForPriceIsABundleNotTheUnitPrice() {
        val tag = shelf(
            at("LOS IMPRECIONANTES", 80, 40, 700, 100),
            at("2X$5.95", 120, 140, 640, 280),
            at("SUAVIZANTE SUAVITEL", 80, 300, 640, 340),
            at("CUIDADO SUPERIOR FRESCA", 80, 350, 680, 390),
            at("PRIMAVERA 1.8 L", 80, 400, 520, 440),
        )
        assertEquals("2.98", tag.price!!.amount.toFixed(2))
        assertEquals(MultiBuyOffer.Kind.BUNDLE, tag.multiBuy!!.kind)
        assertEquals(2, tag.multiBuy!!.quantity)
        assertEquals("5.95", tag.multiBuy!!.price)
        assertNull(tag.regularPrice)
        assertTrue(tag.name!!.contains("SUAVITEL"))
        assertTrue(tag.name!!.contains("1.8 L"))
    }

    @Test
    fun ofertaDoesNotInventAnOldPriceFromTheTwo() {
        val tag = shelf(
            at("SUPER OFERTA", 80, 20, 500, 90),
            at("2X$1.50", 100, 120, 600, 260),
            at("BEBIDA ENERGIZANTE", 80, 280, 560, 320),
            at("RAPTOR 500 mL", 80, 330, 480, 370),
        )
        assertEquals("0.75", tag.price!!.amount.toFixed(2))
        assertNull(tag.regularPrice)
        assertFalse(tag.isPromo)
        assertEquals(MultiBuyOffer.Kind.BUNDLE, tag.multiBuy!!.kind)
        assertTrue(tag.name!!.contains("RAPTOR"))
    }

    @Test
    fun dimensionsAndDatesAreNotATwoForDeal() {
        val tag = shelf(
            at("OFE", 80, 20, 220, 70),
            at("\$6.40", 150, 100, 450, 220),
            at("BOLSA P/ BASURA", 60, 250, 480, 290),
            at("ROLLO NEGRA 15", 60, 300, 420, 340),
            at("UNIDADES 34X48", 60, 350, 420, 390),
            at("DESDE: 02/09/2026", 40, 420, 360, 450),
            at("HASTA: 29/09/2026", 380, 420, 700, 450),
        )
        assertEquals("6.40", tag.price!!.amount.toFixed(2))
        assertNull(tag.multiBuy)
        assertNull(tag.regularPrice)
        val name = tag.name!!
        assertTrue(name.contains("BOLSA"))
        assertTrue(name.contains("34X48") || name.contains("UNIDADES"))
        assertFalse(name.contains("OFE"))
        assertFalse(Advertising.isChoppedBanner("OFE").not())
    }

    @Test
    fun threeLineRailLabelKeepsTheBrandAndSize() {
        val tag = shelf(
            at("LAVAPLATOS", 40, 80, 280, 120),
            at("AXION LIMON 3", 40, 125, 300, 165),
            at("PACK 1,275 g", 40, 170, 280, 210),
            at("\$5.30", 420, 80, 700, 220),
        )
        val name = tag.name!!
        assertTrue(name.contains("LAVAPLATOS"), name)
        assertTrue(name.contains("AXION"), name)
        assertTrue(name.contains("1,275"), name)
        assertEquals("5.30", tag.price!!.amount.toFixed(2))
    }

    @Test
    fun nameBelowAGiantPriceBeatsTheLogoAboveIt() {
        val tag = shelf(
            at("selectos", 40, 10, 900, 180),
            at("\$5.70", 120, 200, 820, 460),
            at("HUEVO SELECTOS", 80, 490, 640, 530),
            at("MEDIANO ROJO", 80, 540, 560, 580),
            at("30 UNIDADES", 80, 590, 480, 630),
        )
        val name = tag.name!!
        assertTrue(name.contains("HUEVO"), name)
        assertTrue(name.contains("30 UNIDADES") || name.contains("MEDIANO"), name)
        assertFalse(name.equals("selectos", ignoreCase = true))
        assertEquals("5.70", tag.price!!.amount.toFixed(2))
    }

    @Test
    fun aRepeatedCountDoesNotBecomeTheOldPrice() {
        val tag = shelf(
            at("CAFE SOLUBLE NESCAFE", 60, 40, 640, 90),
            at("2x$6.50", 80, 120, 700, 320),
            at("LISTO STICK 68 g", 60, 340, 520, 390),
            at("40", 80, 700, 160, 740),
        )
        assertEquals(MultiBuyOffer.Kind.BUNDLE, tag.multiBuy?.kind)
        assertEquals("6.5", tag.multiBuy?.price)
        assertNull(tag.regularPrice)
        assertTrue(tag.name!!.contains("NESCAFE"))
    }

    @Test
    fun aCompleteReadingDoesNotAskForHelp() {
        val tag = shelf(
            at("VINAGRE BLANCO CLEMENTE", 60, 40, 640, 90),
            at("1 L", 60, 100, 240, 140),
            at("\$1.55", 80, 180, 500, 360),
        )
        assertEquals("1.55", tag.price!!.amount.toFixed(2))
        assertTrue(tag.name!!.contains("CLEMENTE"))
        assertFalse(AiReadingCheck.needsHelp(tag))
    }

    @Test
    fun junkIsNotTranslatedAndACheckedReadingMustQuoteTheLabel() {
        assertFalse(NameText.worthTranslating("OFE"))
        assertFalse(NameText.worthTranslating("NubeBlancaCentroamerica"))
        assertTrue(NameText.worthTranslating("VINAGRE BLANCO CLEMENTE 1 L"))
        val ocr = "2X$5.95 SUAVIZANTE SUAVITEL CUIDADO SUPERIOR FRESCA PRIMAVERA 1.8 L"
        val invented = AiReadingCheck.accept(
            AiTagReading(name = "Federal Institute for Employment", amount = "2.00", evidence = listOf("OFE")),
            ocr, "OFE",
        )
        assertTrue(invented == null || invented.name == null)
        assertTrue(invented == null || invented.amount == null)
        val kept = AiReadingCheck.accept(
            AiTagReading(name = "SUAVIZANTE SUAVITEL PRIMAVERA 1.8 L", amount = "5.95", quantity = 2, amountIs = "total", evidence = listOf("2X$5.95")),
            ocr, "SUAVITEL",
        )
        assertNotNull(kept)
        assertEquals("5.95", kept.amount)
        assertTrue(kept.name!!.contains("PRIMAVERA"))
    }

    @Test
    fun aCorrectionIsAppliedTheSecondTime() {
        val memory = OcrMemory()
        assertTrue(memory.learn("histo", "LISTO STICK"))
        val once = memory.apply(OcrFrame(listOf(at("histo", 0, 0, 10, 10)), 100, 100))
        assertEquals("histo", once.lines[0].text)
        memory.learn("histo", "LISTO STICK")
        val twice = memory.apply(OcrFrame(listOf(at("histo", 0, 0, 10, 10)), 100, 100))
        assertEquals("LISTO STICK", twice.lines[0].text)
        assertTrue(memory.userWords.any { it.equals("LISTO", ignoreCase = true) })
    }
}
