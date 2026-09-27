package app.parity.core

import app.parity.core.money.CurrencyCode
import app.parity.core.money.decimal
import app.parity.core.money.toPlain
import app.parity.core.scan.AmountParser
import app.parity.core.scan.Box
import app.parity.core.scan.NameText
import app.parity.core.scan.Names
import app.parity.core.scan.OcrElement
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceStabilizer
import app.parity.core.scan.PriceTagParser
import app.parity.core.scan.PromoDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun line(text: String, l: Int, t: Int, r: Int, b: Int, elements: List<OcrElement> = emptyList()) =
    OcrLine(text, Box(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()), elements)

private fun el(text: String, l: Int, t: Int, r: Int, b: Int) = OcrElement(text, Box(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))

private fun frame(vararg lines: OcrLine) = OcrFrame(lines.toList(), 1000, 1000)

class AmountParserTest {
    private fun parse(raw: String, localDecimals: Int = 2) = AmountParser.findNumbers(raw, localDecimals).map { it.amount.toPlain() }

    @Test
    fun decimalSeparators() {
        assertEquals(listOf("9.99"), parse("9.99"))
        assertEquals(listOf("9.99"), parse("9,99"))
        assertEquals(listOf("1299"), parse("1 299,00"))
        assertEquals(listOf("1299"), parse("1,299.00"))
        assertEquals(listOf("1299"), parse("1.299,00"))
        assertEquals(listOf("1234"), parse("1.234"))
        assertEquals(listOf("1.234"), parse("1.234", localDecimals = 3))
        assertEquals(listOf("1299.5"), parse("1'299.50"))
        assertEquals(listOf("125000"), parse("125.000"))
        assertEquals(listOf("1250000"), parse("1.250.000"))
        assertEquals(listOf("9"), parse("9,-"))
        assertEquals(listOf("3.99"), parse("3 99"))
        assertEquals(listOf("12", "5"), parse("12 5"))
        assertEquals(listOf("4.49", "0.28"), parse("\$4.49 and 0.28"))
    }

    @Test
    fun rejectsDates() {
        assertEquals(emptyList(), parse("12.03.2027"))
    }
}

class PriceTagParserTest {
    @Test
    fun georgianShelfTag() {
        val tag = PriceTagParser.parse(
            frame(
                line("რძე სოფლის 3.2% 1ლ", 100, 100, 500, 140),
                line("3.99 ₾", 150, 180, 400, 300),
                line("1 ლ-ის ფასი: 3.99₾", 100, 320, 400, 350),
                line("4860001234567", 100, 360, 400, 380),
            ),
            CurrencyCode.GEL,
        )
        assertEquals("3.99", tag.price!!.amount.toPlain())
        assertEquals(CurrencyCode.GEL, tag.price!!.currency)
        assertEquals("რძე სოფლის 3.2% 1ლ", tag.name)
        assertFalse(tag.isPromo)
        assertNotNull(tag.nameRegion)
    }

    @Test
    fun superscriptCentsAreMerged() {
        val big = el("3", 100, 100, 160, 220)
        val cents = el("99", 165, 105, 205, 150)
        val tag = PriceTagParser.parse(
            frame(
                line("Khachapuri Imeruli", 60, 20, 400, 60),
                line("3", 100, 100, 160, 220, listOf(big)),
                line("99", 165, 105, 205, 150, listOf(cents)),
            ),
            CurrencyCode.GEL,
        )
        assertEquals("3.99", tag.price!!.amount.toPlain())
        assertEquals("Khachapuri Imeruli", tag.name)
    }

    @Test
    fun unitPriceIsNotTheShelfPrice() {
        val tag = PriceTagParser.parse(
            frame(
                line("Greek Yogurt 16 oz", 50, 40, 450, 80),
                line("\$4.49", 100, 100, 400, 200),
                line("Unit price \$0.28/oz", 50, 220, 450, 250),
            ),
            CurrencyCode.USD,
        )
        assertEquals("4.49", tag.price!!.amount.toPlain())
        assertEquals(CurrencyCode.USD, tag.price!!.currency)
        assertEquals("Greek Yogurt 16 oz", tag.name)
    }

    @Test
    fun saleTagKeepsRegularPrice() {
        val tag = PriceTagParser.parse(
            frame(
                line("SALE", 50, 10, 200, 60),
                line("Coffee beans 500 g", 50, 70, 450, 110),
                line("\$3.49", 100, 130, 400, 250),
                line("was \$4.99", 100, 260, 300, 300),
            ),
            CurrencyCode.USD,
        )
        assertTrue(tag.isPromo)
        assertEquals("3.49", tag.price!!.amount.toPlain())
        assertEquals("4.99", tag.regularPrice!!.toPlain())
        assertEquals("Coffee beans 500 g", tag.name)
    }

    @Test
    fun fatPercentageIsNotASale() {
        val tag = PriceTagParser.parse(
            frame(
                line("Sour cream 20% 400g", 50, 40, 450, 80),
                line("9,99", 100, 100, 400, 220),
            ),
            CurrencyCode.GEL,
        )
        assertEquals("9.99", tag.price!!.amount.toPlain())
        assertFalse(tag.isPromo)
        assertNull(tag.price!!.currency)
    }

    @Test
    fun discountPercentIsASaleSignal() {
        val promo = PromoDetector.detect(listOf(line("-20%", 0, 0, 10, 10), line("ფასდაკლება", 0, 20, 10, 30)))
        assertTrue(promo.isPromo)
        assertTrue("-20%" in promo.signals)
        assertTrue("ფასდაკლება" in promo.signals)
    }

    @Test
    fun europeanGroupingWithEuroSign() {
        val tag = PriceTagParser.parse(frame(line("1 299,00 €", 100, 100, 500, 200)), CurrencyCode.EUR)
        assertEquals("1299", tag.price!!.amount.toPlain())
        assertEquals(CurrencyCode.EUR, tag.price!!.currency)
    }

    @Test
    fun datesAndBarcodesAreIgnored() {
        val tag = PriceTagParser.parse(
            frame(
                line("Best before 12.03.2027", 50, 10, 400, 40),
                line("4860001234567", 50, 50, 400, 80),
                line("2.49", 100, 100, 300, 180),
            ),
            CurrencyCode.GEL,
        )
        assertEquals("2.49", tag.price!!.amount.toPlain())
    }

    @Test
    fun emptyFrameHasNoPrice() {
        assertNull(PriceTagParser.parse(frame(line("Welcome", 0, 0, 100, 30)), CurrencyCode.GEL).price)
    }
}

class StabilizerAndNamesTest {
    @Test
    fun locksAfterThreeMatchingFrames() {
        val a = PriceTagParser.parse(frame(line("3.99 ₾", 100, 100, 400, 200)), CurrencyCode.GEL)
        val b = PriceTagParser.parse(frame(line("4.49 ₾", 100, 100, 400, 200)), CurrencyCode.GEL)
        val s = PriceStabilizer(3)
        assertNull(s.offer(a))
        assertNull(s.offer(a))
        assertNotNull(s.offer(a))
        assertNull(s.offer(a)) // already locked, not re-announced
        assertNull(s.offer(b))
        assertNull(s.offer(b))
        assertEquals("4.49", s.offer(b)!!.price!!.amount.toPlain())
    }

    @Test
    fun normalizesNames() {
        assertEquals("sour cream 400g", Names.normalize("Sour Cream, 400 g!"))
        assertEquals("რძე 3.2% 1ლ".let { Names.normalize(it) }, "რძე 3.2 1ლ")
        assertTrue(Names.similarity("sour cream 400g", "sour creme 400g") > 0.92)
        assertEquals(listOf("tomato", "berry", "egg"), Names.tokens("Tomatoes berries eggs"))
    }

    @Test
    fun decimalsHelper() {
        assertEquals("2.5", decimal("2.50").toPlain())
    }
}

class NameTextTest {
    @Test
    fun keepsLinesInTheLabelScript() {
        assertEquals("ხაჭაპური იმერული", NameText.pickLines("ხაჭაპური იმერული\nAQ ar", "ka"))
        assertEquals("ყავა Jacobs Monarch 95გ", NameText.pickLines("ყავა Jacobs Monarch 95გ", "ka"))
        assertEquals("რძე სოფლის 3.2% 1 ლიტრი", NameText.pickLines("==.\nრძე სოფლის 3.2%\n1 ლიტრი", "ka"))
        assertNull(NameText.pickLines("==.\nAQ ar", "ka"))
    }

    @Test
    fun latinNamesNeedRealWords() {
        assertEquals("Greek Yogurt 16 oz", NameText.pickLines("Greek Yogurt 16 oz", null))
        assertNull(NameText.pickLines("AQ ar\n4 49", null))
    }
}
