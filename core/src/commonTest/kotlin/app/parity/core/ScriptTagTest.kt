package app.parity.core

import app.parity.core.money.Countries
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Languages
import app.parity.core.money.decimal
import app.parity.core.money.toFixed
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.MultiBuyOffer
import app.parity.core.scan.MultiBuyOffer.Kind
import app.parity.core.scan.NameText
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceTagParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))

class LabelLanguageTest {
    @Test
    fun labelsFollowTheCountry() {
        assertEquals("ka", Languages.labelLanguageFor("GE", CurrencyCode.GEL))
        assertEquals("ru", Languages.labelLanguageFor("RU", CurrencyCode("RUB")))
        assertEquals("de", Languages.labelLanguageFor("DE", CurrencyCode("EUR")))
        assertEquals("en", Languages.labelLanguageFor("US", CurrencyCode.USD))
    }

    @Test
    fun aLocalCurrencySetByHandBringsItsCountrysLabels() {
        // Testing Georgian tags at home: located in the US, local currency set to lari.
        assertEquals("ka", Languages.labelLanguageFor("US", CurrencyCode.GEL))
        assertEquals("ka", Languages.labelLanguageFor(null, CurrencyCode.GEL))
        // A shop in Georgia pricing in dollars still has Georgian labels.
        assertEquals("ka", Languages.labelLanguageFor("GE", CurrencyCode.USD))
        // The euro has no single home country, so the country decides.
        assertEquals("en", Languages.labelLanguageFor("US", CurrencyCode("EUR")))
        // Shops in Cambodia price in dollars; the labels are still Khmer.
        assertEquals("km", Languages.labelLanguageFor("KH", CurrencyCode.USD))
    }

    @Test
    fun homeCountryComesFromTheCurrencyCode() {
        assertEquals("GE", Countries.homeCountryOf(CurrencyCode.GEL))
        assertEquals("US", Countries.homeCountryOf(CurrencyCode.USD))
        assertNull(Countries.homeCountryOf(CurrencyCode("EUR")))
        assertNull(Countries.homeCountryOf(CurrencyCode("BTC")))
    }
}

/**
 * A Georgian tag photographed on a monitor, with the photo editor's "Visible layers" label at the
 * edge of the frame (a real report). The Latin recognizer can't read the tag's name, so its best
 * "name" is the editor's label; that must never become the product name.
 */
class StrayLatinTextTest {
    private val latinView = listOf(
        l("38@gn, 1 3o", 245, 500, 610, 545),
        l("C9.99", 355, 570, 610, 640),
        l("e layers", 0, 790, 58, 815),
    )

    private fun firstPass() = PriceTagParser.parse(OcrFrame(latinView, 610, 835), CurrencyCode.GEL)

    @Test
    fun theLatinRecognizersNameIsDroppedOnTheScriptPass() {
        val first = firstPass()
        assertEquals("9.99", first.price!!.amount.toPlain())
        assertEquals("e layers", first.name)
        // The script-aware OCR found no text at all: no name, rather than the stray label.
        assertNull(PriceTagParser.refine(first, emptyList(), CurrencyCode.GEL, "ka").name)
        // It found only the price: still no name.
        assertNull(PriceTagParser.refine(first, listOf(l("09.99", 355, 570, 610, 640)), CurrencyCode.GEL, "ka").name)
    }

    @Test
    fun theScriptPassReadsTheName() {
        val refined = PriceTagParser.refine(
            firstPass(),
            listOf(l("ვაშლი, 1 კი;", 245, 500, 610, 545), l("09.99", 355, 570, 610, 640)),
            CurrencyCode.GEL, "ka",
        )
        assertEquals("ვაშლი, 1 კი;", refined.name)
        assertEquals("9.99", refined.price!!.amount.toPlain())
    }

    @Test
    fun textInTheLabelsScriptBeatsLatinText() {
        val refined = PriceTagParser.refine(
            firstPass(),
            listOf(
                l("Visible layers", 245, 440, 610, 485),
                l("ვაშლი", 245, 500, 400, 545),
                l("09.99", 355, 570, 610, 640),
            ),
            CurrencyCode.GEL, "ka",
        )
        assertEquals("ვაშლი", refined.name)
    }

    @Test
    fun theNameAreaStaysOnTheTag() {
        val region = firstPass().nameRegion!!
        // Above the price, not around the editor's label in the corner.
        assertTrue(region.bottom <= 570f, "region $region")
        assertTrue(region.top < 545f && region.right > 400f, "region $region")
    }

    @Test
    fun scriptHelpers() {
        assertTrue(NameText.isInScript("ქოქოსის რძე", "ka"))
        assertFalse(NameText.isInScript("sblelayers", "ka"))
        assertFalse(NameText.isInScript("Coconut milk", null))
    }
}

/** "ქოქოსის რძე / შეიძინეთ 3 ცალი / ₾11.20-ად": buy 3 for ₾11.20, with no single price (a real report). */
class DealOnlyTagTest {
    /** The Latin recognizer read "ც" (in "ცალი") as a 6. */
    private val latinView = listOf(
        l("Jmjmbob mdg", 313, 102, 626, 147),
        l("dgodobgo 3 6sem", 258, 159, 668, 203),
        l("C11.20-sQ", 223, 222, 646, 302),
    )

    private fun firstPass() = PriceTagParser.parse(OcrFrame(latinView, 700, 480), CurrencyCode.GEL)

    private fun georgianView(priceLine: String) = listOf(
        l("ქოქოსის რძე", 313, 102, 626, 147),
        l("შეიძინეთ 3 ცალი", 258, 159, 668, 203),
        l(priceLine, 223, 222, 646, 302),
    )

    @Test
    fun theBundleIsTheOnlyPrice() {
        val first = firstPass()
        assertEquals("11.2", first.price!!.amount.toPlain())
        assertNull(first.multiBuy)

        val refined = PriceTagParser.refine(first, georgianView("₾11.20-ად"), CurrencyCode.GEL, "ka")
        assertEquals(MultiBuyOffer.dealOnly(3, decimal("11.20"), each = false), refined.multiBuy)
        assertEquals(Kind.BUNDLE, refined.multiBuy!!.kind)
        assertFalse(refined.multiBuy!!.singlePriceShown)
        // The deal's unit price stands in for the missing single price.
        assertEquals("3.73", refined.price!!.amount.toPlain())
        assertFalse(refined.isPromo)
        // The deal wording isn't part of the name.
        assertEquals("ქოქოსის რძე", refined.name)
    }

    @Test
    fun aMisreadPriceLineStillMakesTheBundle() {
        // Tesseract's actual reading of the price line in the report.
        val refined = PriceTagParser.refine(firstPass(), georgianView("(41 .20-s¢0"), CurrencyCode.GEL, "ka")
        assertEquals(Kind.BUNDLE, refined.multiBuy!!.kind)
        assertEquals("11.2", refined.multiBuy!!.price)
        assertEquals("3.73", refined.price!!.amount.toPlain())
    }

    @Test
    fun theMisreadLetterIsNotADealPrice() {
        val refined = PriceTagParser.refine(firstPass(), georgianView("₾11.20-ად"), CurrencyCode.GEL, "ka")
        assertNotEquals(MultiBuyOffer.each(3, decimal("6")), refined.multiBuy)
        assertTrue(refined.alternatives.none { it.amount.toPlain() == "6" })
    }

    @Test
    fun englishBuyTwoForFive() {
        val tag = PriceTagParser.parse(
            OcrFrame(listOf(l("Greek Yogurt 5.3 oz", 50, 20, 450, 60), l("Buy 2 for \$5", 100, 100, 500, 220)), 1000, 1000),
            CurrencyCode.USD,
        )
        assertEquals(Kind.BUNDLE, tag.multiBuy!!.kind)
        assertFalse(tag.multiBuy!!.singlePriceShown)
        assertEquals("2.5", tag.price!!.amount.toPlain())
    }

    @Test
    fun anAgeRatingIsNotADeal() {
        val tag = PriceTagParser.parse(
            OcrFrame(listOf(l("Puzzle 3+", 50, 20, 450, 60), l("\$19.99", 100, 100, 500, 220)), 1000, 1000),
            CurrencyCode.USD,
        )
        assertNull(tag.multiBuy)
        assertEquals("19.99", tag.price!!.amount.toPlain())
    }

    @Test
    fun dealOnlyOffersRoundTripAndOldOffersStayCompact() {
        val offer = MultiBuyOffer.dealOnly(3, decimal("11.20"), each = false)
        assertEquals(offer, MultiBuyOffer.fromJson(offer.toJson()))
        assertFalse(MultiBuyOffer.bundle(3, decimal("10")).toJson().contains("singlePriceShown"))
        assertEquals("11.20", offer.total(3, offer.unitStandIn(2)).toFixed(2))
    }
}

/** Live scanning ignores numbers that aren't written like shelf prices (a real report). */
class StrictScanTest {
    private fun strict(local: CurrencyCode, vararg lines: OcrLine) =
        PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), local, strict = true)

    @Test
    fun strayNumbersMakeNoTag() {
        // A photo editor's ruler and panel label.
        assertNull(strict(CurrencyCode.GEL, l("250", 70, 200, 95, 260), l("500", 70, 500, 95, 560), l("Visible layers", 0, 790, 140, 815)).price)
        // A calendar.
        assertNull(strict(CurrencyCode.USD, l("September 2026", 100, 100, 700, 180), l("27", 300, 300, 500, 520)).price)
        // A number in running text.
        assertNull(strict(CurrencyCode.USD, l("Walk 2.50 miles along the river to the lake", 50, 100, 950, 140)).price)
        // A page of text with a figure no bigger than the words around it.
        assertNull(
            strict(
                CurrencyCode.USD,
                l("The museum opens at nine and closes", 50, 100, 950, 140),
                l("at six. Tickets cost 12.50 for adults", 50, 150, 950, 190),
                l("and are free for children under", 50, 200, 950, 240),
                l("twelve. Guided tours run hourly.", 50, 250, 950, 290),
                l("12.50", 400, 300, 520, 340),
            ).price,
        )
    }

    @Test
    fun shelfTagsStillScan() {
        assertEquals(
            "2.49",
            strict(CurrencyCode.GEL, l("აქცია 1+1", 50, 20, 450, 70), l("ლიმონათი ნატახტარი", 50, 80, 450, 120), l("2.49 ₾", 100, 150, 400, 260))
                .price!!.amount.toPlain(),
        )
        assertEquals("3.99", strict(CurrencyCode.USD, l("Greek Yogurt 5.3 oz", 50, 20, 450, 60), l("\$3.99", 100, 100, 400, 220)).price!!.amount.toPlain())
        // A whole-lari price with its sign.
        assertEquals("5", strict(CurrencyCode.GEL, l("პური", 50, 20, 300, 60), l("5 ₾", 100, 100, 300, 220)).price!!.amount.toPlain())
        // What the Latin recognizer makes of a Georgian tag on a screen.
        assertEquals(
            "9.99",
            strict(CurrencyCode.GEL, l("A", 25, 76, 39, 93), l("elayers", 2, 793, 60, 817), l("3odoo, 1 300", 244, 490, 623, 559), l("(h9.99", 352, 560, 608, 647))
                .price!!.amount.toPlain(),
        )
    }

    @Test
    fun photosAreNotStrict() {
        val tag = PriceTagParser.parse(OcrFrame(listOf(l("Bread", 50, 20, 300, 60), l("5", 100, 100, 200, 220)), 1000, 1000), CurrencyCode.GEL)
        assertEquals("5", tag.price!!.amount.toPlain())
    }
}

/** Decimal commas ("9,99 €", "89,90 ₽", "11,20-ად") read the same as decimal points. */
class DecimalCommaTest {
    private fun strict(local: CurrencyCode, vararg lines: OcrLine) =
        PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), local, strict = true)

    @Test
    fun commaPricesPassTheLiveScan() {
        assertEquals("0.99", strict(CurrencyCode("EUR"), l("Vollmilch 3,5% 1L", 50, 20, 450, 60), l("0,99 €", 100, 100, 400, 220)).price!!.amount.toPlain())
        assertEquals("9.99", strict(CurrencyCode.GEL, l("ყავა 95გ", 50, 20, 450, 60), l("9,99 ₾", 100, 100, 400, 220)).price!!.amount.toPlain())
        assertEquals("1299", strict(CurrencyCode("EUR"), l("Fernseher 55 Zoll", 50, 20, 450, 60), l("1.299,00 €", 100, 100, 500, 220)).price!!.amount.toPlain())
        assertEquals("9", strict(CurrencyCode("EUR"), l("Brot", 50, 20, 300, 60), l("9,-", 100, 100, 300, 220)).price!!.amount.toPlain())
    }

    @Test
    fun commaDeals() {
        val tag = strict(
            CurrencyCode("RUB"),
            l("Молоко 3,2% 1л", 50, 20, 450, 60),
            l("89,90 ₽", 100, 100, 400, 220),
            l("от 3 шт. по 79,90", 100, 240, 400, 280),
        )
        assertEquals("89.9", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.EACH, 3, price = "79.9"), tag.multiBuy)

        val first = PriceTagParser.parse(
            OcrFrame(listOf(l("Jmjmbob mdg", 313, 102, 626, 147), l("dgodobgo 3 6sem", 258, 159, 668, 203), l("C11,20-sQ", 223, 222, 646, 302)), 700, 480),
            CurrencyCode.GEL,
        )
        val refined = PriceTagParser.refine(
            first,
            listOf(l("ქოქოსის რძე", 313, 102, 626, 147), l("შეიძინეთ 3 ცალი", 258, 159, 668, 203), l("₾11,20-ად", 223, 222, 646, 302)),
            CurrencyCode.GEL, "ka",
        )
        assertEquals(MultiBuyOffer.dealOnly(3, decimal("11.20"), each = false), refined.multiBuy)
        assertEquals("3.73", refined.price!!.amount.toPlain())
    }
}

/** What Tesseract made of a juice tag: a half-read name, the price, and the price per litre. */
class UnitPriceLineTest {
    private val first = PriceTagParser.parse(
        OcrFrame(listOf(l("mmmn 6Bgbo", 170, 480, 640, 540), l("4.49 «", 280, 560, 780, 700), l("1 m-ob gsbo: 4.49 C", 170, 720, 900, 760)), 1080, 1200),
        CurrencyCode.GEL,
    )

    @Test
    fun thePricePerLitreIsNotTheName() {
        val refined = PriceTagParser.refine(
            first,
            listOf(l("ee წვენი", 170, 480, 640, 540), l("4.49 «", 280, 560, 780, 700), l("1 ლ-ის ფასი: 4.49 რ IIIIIIIIIIIIIIII", 170, 720, 900, 760)),
            CurrencyCode.GEL, "ka",
        )
        assertEquals("ee წვენი", refined.name)
        assertEquals("4.49", refined.price!!.amount.toPlain())
    }

    @Test
    fun barcodeStripesAreNotPartOfTheName() {
        val refined = PriceTagParser.refine(
            first,
            listOf(l("ფორთოხლის წვენი IIIIIIIIII", 170, 480, 640, 540), l("4.49 «", 280, 560, 780, 700)),
            CurrencyCode.GEL, "ka",
        )
        assertEquals("ფორთოხლის წვენი", refined.name)
    }

    @Test
    fun aWeightInTheNameIsStillTheName() {
        val tag = PriceTagParser.parse(
            OcrFrame(listOf(l("Bananas 1 kg", 50, 20, 450, 60), l("\$1.99", 100, 100, 400, 220)), 1000, 1000),
            CurrencyCode.USD,
        )
        assertEquals("Bananas 1 kg", tag.name)
    }
}

/** What Tesseract makes of tags on a phone's live camera: near-misses, stray marks, tight rows. */
class LiveCameraReadingTest {
    @Test
    fun aNameWhoseLettersReachThePriceIsStillTheName() {
        val first = PriceTagParser.parse(
            OcrFrame(listOf(l("3odgo, 1 3ogmgmsd", 120, 583, 540, 606), l("(h9.99", 200, 612, 450, 660)), 660, 1280),
            CurrencyCode.GEL,
        )
        // Tesseract's box for the Georgian line reaches 8 px into the price's box.
        val refined = PriceTagParser.refine(
            first, listOf(l("ვაშლი, 1 კილოგრამ", 118, 580, 545, 620), l("(h 9.99", 198, 612, 452, 660)), CurrencyCode.GEL, "ka",
        )
        assertEquals("ვაშლი, 1 კილოგრამ", refined.name)
    }

    @Test
    fun strayMarksAreTrimmed() {
        assertEquals("ვაშლი, 1 კილოგრამი", NameText.trimMarks("| ვაშლი, 1 კილოგრამი"))
        assertEquals("ილოგრა", NameText.trimMarks("ილოგრა ("))
        assertEquals("ამლი, 1 კილოოგ", NameText.trimMarks("ამლი,; 1 კილოოგ!"))
        assertEquals("Morötter (5)", NameText.trimMarks("Morötter (5)"))
        assertEquals("რძე 3.2%", NameText.trimMarks("რძე 3.2%"))
    }

    @Test
    fun misspelledDealWordsStillMakeTheDeal() {
        val first = PriceTagParser.parse(
            OcrFrame(listOf(l("Jmjmbob mdg", 313, 102, 626, 147), l("dgodobgo 3 6sem", 258, 159, 668, 203), l("C11.20-sQ", 223, 222, 646, 302)), 700, 480),
            CurrencyCode.GEL,
        )
        // "შეიძინეთ" read with a wrong letter, "ცალი" with one missing.
        val refined = PriceTagParser.refine(
            first,
            listOf(l("ქოქოსის რძე", 313, 102, 626, 147), l("შეიძინეტ 3 ცალ", 258, 159, 668, 203), l("₾11.20-ად", 223, 222, 646, 302)),
            CurrencyCode.GEL, "ka",
        )
        assertEquals(Kind.BUNDLE, refined.multiBuy!!.kind)
        assertEquals("ქოქოსის რძე", refined.name)
    }

    @Test
    fun misspelledSaleWordsStillMakeASale() {
        val tag = PriceTagParser.parse(
            OcrFrame(listOf(l("ფასდაკლებს", 50, 10, 450, 60), l("ყავა 95გ", 50, 70, 450, 110), l("9.99 ₾", 100, 130, 400, 250), l("12.49", 100, 260, 300, 300)), 1000, 1000),
            CurrencyCode.GEL,
        )
        assertTrue(tag.isPromo)
        assertEquals("12.49", tag.regularPrice!!.toPlain())
    }

    @Test
    fun latinReadingsAgree() {
        assertTrue(NameText.sameLatinText("Pringles Original", "Pringies Original"))
        kotlin.test.assertFalse(NameText.sameLatinText("Penang. 1 –", "3odgo, 1 3ogmgmsd"))
    }
}

class ScriptAreaTest {
    @Test
    fun theAreaTakesInWholeLinesAroundThePrice() {
        val tag = PriceTagParser.parse(
            OcrFrame(listOf(l("3odgo, 1 3ogmgmsd", 40, 583, 540, 606), l("9.99", 250, 612, 380, 660), l("Visible layers", 0, 1200, 140, 1230)), 660, 1280),
            CurrencyCode.GEL,
        )
        val area = PriceTagParser.scriptArea(tag)!!
        // The name line starts far left of the price; the editor's label further down stays out.
        assertTrue(area.left <= 40f, "area $area")
        assertTrue(area.bottom < 1200f, "area $area")
    }
}

class FocusedReadTest {
    @Test
    fun dealWordingIsLeftOutOfTheName() {
        assertEquals("ქოქოსის რძე", PriceTagParser.pickName("ქოქოსის რძე\nშეიძინეთ 3 ცალი\nRRAA რრლ ...", "ka"))
        assertEquals("ლიმონათი ნატახტარი", PriceTagParser.pickName("აქცია 1+1\nლიმონათი ნატახტარი", "ka"))
        // A pack size belongs to the name.
        assertEquals("კვერცხი 10 ცალი", PriceTagParser.pickName("კვერცხი 10 ცალი", "ka"))
    }
}
