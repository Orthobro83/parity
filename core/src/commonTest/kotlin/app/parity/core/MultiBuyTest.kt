package app.parity.core

import app.parity.core.money.CurrencyCode
import app.parity.core.money.decimal
import app.parity.core.money.toFixed
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.MultiBuyOffer
import app.parity.core.scan.MultiBuyOffer.Kind
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceTagParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))

private fun parse(local: CurrencyCode, vararg lines: OcrLine) = PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), local)

class MultiBuyParsingTest {
    @Test
    fun georgianFromThreeEach() {
        val tag = parse(
            CurrencyCode.GEL,
            l("რძე სოფლის 1ლ", 100, 100, 500, 140),
            l("9.99 ₾", 150, 180, 450, 300),
            l("3 ცალის ყიდვისას", 100, 330, 450, 370),
            l("7.99 ₾/ცალი", 100, 380, 400, 440),
        )
        assertEquals("9.99", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.EACH, 3, price = "7.99"), tag.multiBuy)
        assertFalse(tag.isPromo)
    }

    @Test
    fun dealPrintedBiggerThanRegularPrice() {
        val tag = parse(
            CurrencyCode.GEL,
            l("Pasta Barilla 500 g", 50, 20, 450, 60),
            l("3+ 7.99 ₾", 100, 100, 500, 220),
            l("1 ც 9.99 ₾", 100, 240, 400, 280),
        )
        assertEquals("9.99", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.EACH, 3, price = "7.99"), tag.multiBuy)
    }

    @Test
    fun englishBundleTotal() {
        val tag = parse(
            CurrencyCode.USD,
            l("Greek Yogurt 5.3 oz", 50, 20, 450, 60),
            l("\$3.99", 100, 100, 400, 220),
            l("3 for \$10", 100, 240, 350, 280),
        )
        assertEquals("3.99", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.BUNDLE, 3, price = "10"), tag.multiBuy)
    }

    @Test
    fun onePlusOneIsADealNotASale() {
        val tag = parse(
            CurrencyCode.GEL,
            l("აქცია 1+1", 50, 20, 450, 70),
            l("ლიმონათი ნატახტარი", 50, 80, 450, 120),
            l("4.49 ₾", 100, 150, 400, 260),
        )
        assertEquals("4.49", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.FREE, 2, paidUnits = 1), tag.multiBuy)
        assertFalse(tag.isPromo)
    }

    @Test
    fun secondUnitDiscount() {
        val tag = parse(
            CurrencyCode.USD,
            l("Shampoo 400 ml", 50, 20, 450, 60),
            l("\$5.00", 100, 100, 400, 220),
            l("2nd at 50% off", 100, 240, 400, 280),
        )
        assertEquals("5", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.SECOND_UNIT, 2, percentOff = 50), tag.multiBuy)
        assertFalse(tag.isPromo)
    }

    @Test
    fun russianFromThreeAt() {
        val tag = parse(
            CurrencyCode("RUB"),
            l("Молоко 3.2% 1л", 50, 20, 450, 60),
            l("89.90 ₽", 100, 100, 400, 220),
            l("от 3 шт. по 79.90", 100, 240, 400, 280),
        )
        assertEquals("89.9", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.EACH, 3, price = "79.9"), tag.multiBuy)
    }

    @Test
    fun usTwoForFive() {
        val tag = parse(
            CurrencyCode.USD,
            l("Avocados", 50, 20, 450, 60),
            l("\$2.99", 100, 100, 400, 220),
            l("2/\$5", 100, 240, 300, 280),
        )
        assertEquals("2.99", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.BUNDLE, 2, price = "5"), tag.multiBuy)
    }

    @Test
    fun packSizeIsNotADeal() {
        val tag = parse(
            CurrencyCode.USD,
            l("Eggs large 10 pcs", 50, 20, 450, 60),
            l("\$3.99", 100, 100, 400, 220),
        )
        assertEquals("3.99", tag.price!!.amount.toPlain())
        assertNull(tag.multiBuy)
    }

    @Test
    fun salesStillDetectedWithoutDeals() {
        val tag = parse(
            CurrencyCode.GEL,
            l("ფასდაკლება -20%", 50, 10, 450, 60),
            l("ყავა 95გ", 50, 70, 450, 110),
            l("9.99 ₾", 100, 130, 400, 250),
            l("12.49", 100, 260, 300, 300),
        )
        assertEquals("9.99", tag.price!!.amount.toPlain())
        assertEquals("12.49", tag.regularPrice!!.toPlain())
        assertNull(tag.multiBuy)
        kotlin.test.assertTrue(tag.isPromo)
    }
}

class MultiBuyMathTest {
    private val gel = CurrencyCode.GEL

    @Test
    fun eachAppliesFromTheThreshold() {
        val offer = MultiBuyOffer.each(3, decimal("7.99"))
        assertEquals("23.97", offer.total(3, decimal("9.99")).toFixed(2))
        assertEquals("19.98", offer.total(2, decimal("9.99")).toFixed(2))
        assertEquals("31.96", offer.total(4, decimal("9.99")).toFixed(2))
        assertEquals("7.99", offer.dealUnitPrice(decimal("9.99")).toFixed(2))
        assertEquals("3+ at ₾7.99 each", offer.describe(gel))
    }

    @Test
    fun bundlesChargeTheRestAtRegularPrice() {
        val offer = MultiBuyOffer.bundle(3, decimal("10"))
        assertEquals("13.99", offer.total(4, decimal("3.99")).toFixed(2))
        assertEquals("3.33", offer.dealUnitPrice(decimal("3.99")).toFixed(2))
        assertEquals(3, offer.step)
    }

    @Test
    fun freeAndSecondUnitDeals() {
        val twoPlusOne = MultiBuyOffer(Kind.FREE, 3, paidUnits = 2)
        assertEquals("8.98", twoPlusOne.total(3, decimal("4.49")).toFixed(2))
        assertEquals("13.47", twoPlusOne.total(4, decimal("4.49")).toFixed(2))
        assertEquals("Buy 2, get 1 free", twoPlusOne.describe(gel))
        val half = MultiBuyOffer(Kind.SECOND_UNIT, 2, percentOff = 50)
        assertEquals("7.50", half.total(2, decimal("5")).toFixed(2))
        assertEquals("12.50", half.total(3, decimal("5")).toFixed(2))
    }

    @Test
    fun resolvesEachOrBundleAgainstTheRegularPrice() {
        assertEquals(Kind.EACH, MultiBuyOffer.resolve(3, decimal("7.99"), decimal("9.99"))!!.kind)
        assertEquals(Kind.BUNDLE, MultiBuyOffer.resolve(3, decimal("20"), decimal("7.99"))!!.kind)
        assertNull(MultiBuyOffer.resolve(3, decimal("40"), decimal("9.99")))
        assertNotNull(MultiBuyOffer.fromJson(MultiBuyOffer.each(3, decimal("7.99")).toJson()))
    }
}

class RefineWithScriptOcrTest {
    /** What the Latin recognizer makes of a Georgian deal tag: the digits survive, the words don't. */
    private val latinView = listOf(
        l("dsjsmmba Barilla 500g", 90, 250, 640, 310),
        l("9.99 C", 180, 390, 780, 550),
        l("3 gsgol yogzolsol", 110, 640, 660, 700),
        l("7.99 C / gsgo", 110, 740, 790, 860),
    )

    /** Tesseract's reading of the same frame. */
    private val georgianView = listOf(
        l("მაკარონი Barilla 500გ", 90, 250, 640, 310),
        l("9.99 ₾", 180, 390, 780, 550),
        l("3 ცალის ყიდვისას", 110, 640, 660, 700),
        l("7.99 ₾ / ცალი", 110, 740, 790, 860),
    )

    @Test
    fun georgianDealIsFoundOnTheSecondPass() {
        val first = parse(CurrencyCode.GEL, *latinView.toTypedArray())
        assertEquals("9.99", first.price!!.amount.toPlain())
        assertNull(first.multiBuy)
        val refined = PriceTagParser.refine(first, georgianView)
        assertEquals("9.99", refined.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.EACH, 3, price = "7.99"), refined.multiBuy)
        assertFalse(refined.isPromo)
    }

    @Test
    fun georgianSaleWordsAreFoundOnTheSecondPass() {
        val first = parse(
            CurrencyCode.GEL,
            l("gsbwsjgmgbs", 50, 10, 450, 60),
            l("ysgs 95g", 50, 70, 450, 110),
            l("9.99", 100, 130, 400, 250),
            l("12.49", 100, 260, 300, 300),
        )
        kotlin.test.assertFalse(first.isPromo)
        val refined = PriceTagParser.refine(
            first,
            listOf(
                l("ფასდაკლება", 50, 10, 450, 60),
                l("ყავა 95გ", 50, 70, 450, 110),
                l("9.99", 100, 130, 400, 250),
                l("ძველი ფასი 12.49", 100, 260, 300, 300),
            ),
        )
        kotlin.test.assertTrue(refined.isPromo)
        assertEquals("9.99", refined.price!!.amount.toPlain())
        assertEquals("12.49", refined.regularPrice!!.toPlain())
    }
}

class DealNameTest {
    @Test
    fun promotionBannerIsNotTheName() {
        val first = parse(
            CurrencyCode.GEL,
            l("sjgos 1+1", 60, 190, 1020, 310),
            l("gomsbsom bsGsbGsmo", 90, 370, 700, 430),
            l("2.49 C", 180, 520, 850, 720),
        )
        val refined = PriceTagParser.refine(
            first,
            listOf(
                l("აქცია 1+1", 60, 190, 1020, 310),
                l("ლიმონათი ნატახტარი", 90, 370, 700, 430),
                l("2.49 «", 180, 520, 850, 720),
            ),
        )
        assertEquals("ლიმონათი ნატახტარი", refined.name)
        assertEquals(MultiBuyOffer(Kind.FREE, 2, paidUnits = 1), refined.multiBuy)
    }
}
