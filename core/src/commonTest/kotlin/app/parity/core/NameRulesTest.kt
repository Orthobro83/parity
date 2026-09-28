package app.parity.core

import app.parity.core.money.CurrencyCode
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceTagParser
import app.parity.core.scan.StorePhrases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int, confidence: Float? = null) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), confidence = confidence)

private fun tag(local: String, vararg lines: OcrLine, phrases: Set<String> = emptySet()) =
    PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), CurrencyCode(local), storePhrases = phrases)

/** Advertising, deal wording and units are never the name (design §6.2); letter case never decides alone. */
class PromoIsNotANameTest {
    @Test
    fun bannersAboveTheProductInSeveralLanguages() {
        // Each banner is printed bigger than the name and right above the price.
        for (banner in listOf("SUPER DISCOUNT", "ფასდაკლება", "2 for 1", "СКИДКА", "¡OFERTA 2x1!", "-30%", "50% OFF", "BOGO", "Precio bajo")) {
            val t = tag("USD", l("Chicken breast fillet", 80, 60, 700, 110), l(banner, 60, 130, 900, 250), l("\$9.99", 150, 270, 600, 470))
            assertEquals("Chicken breast fillet", t.name, "banner $banner")
        }
    }

    @Test
    fun allTheBannersAtOnce() {
        val t = tag(
            "GEL",
            l("SUPER DISCOUNT", 60, 20, 900, 90), l("ფასდაკლება", 60, 100, 700, 160), l("2 for 1", 60, 170, 500, 230),
            l("ქათმის ფილე 1 კგ", 80, 250, 800, 300), l("9.99 ₾", 150, 330, 600, 520),
        )
        assertEquals("ქათმის ფილე 1 კგ", t.name)
        assertTrue(t.isPromo || t.multiBuy != null)
    }

    @Test
    fun anAllCapsProductIsStillTheName() {
        val t = tag("USD", l("SALE", 60, 20, 500, 120), l("CHICKEN BREAST", 80, 140, 800, 200), l("\$6.49", 150, 230, 600, 430))
        assertEquals("CHICKEN BREAST", t.name)
        assertTrue(t.isPromo)
        // Capitals alone aren't a banner: the name in capitals beats a mixed-case line under it.
        val u = tag("USD", l("CHICKEN BREAST", 80, 40, 800, 110), l("Farm fresh", 80, 120, 400, 150), l("\$6.49", 150, 230, 600, 430))
        assertEquals("CHICKEN BREAST", u.name)
    }

    @Test
    fun capitalsBreakATieOnlyAgainstABanner() {
        // Scored within a whisker: two words in capitals alone atop the tag are a banner.
        val t = tag("USD", l("FARMHOUSE FRESHNESS", 80, 60, 560, 95), l("Chicken wings", 80, 120, 520, 170), l("\$4.99", 150, 200, 600, 400))
        assertEquals("Chicken wings", t.name)
        // The same line in mixed case isn't a banner: it wins, and the line under it joins it.
        val u = tag("USD", l("Farmhouse freshness", 80, 60, 560, 95), l("Chicken wings", 80, 120, 520, 170), l("\$4.99", 150, 200, 600, 400))
        assertEquals("Farmhouse freshness Chicken wings", u.name)
    }

    @Test
    fun unitsAndWeightsAreNotNames() {
        for (units in listOf("წონა 1 კგ", "each", "per kg", "ცალი", "Вес 1 кг", "Peso neto")) {
            val t = tag("GEL", l(units, 80, 40, 800, 120), l("ყველი სულგუნი", 80, 140, 700, 180), l("12.99 ₾", 150, 220, 600, 420))
            assertEquals("ყველი სულგუნი", t.name, "units $units")
        }
        // A weight in the name stays part of it.
        assertEquals("Bananas 1 kg", tag("USD", l("Bananas 1 kg", 50, 20, 450, 60), l("\$1.99", 100, 100, 400, 220)).name)
    }

    @Test
    fun wasAndNowAreNotNames() {
        val t = tag("USD", l("Greek Yogurt 16 oz", 50, 20, 450, 60), l("NOW \$3.49", 100, 100, 500, 220), l("was \$4.99", 100, 240, 300, 280))
        assertEquals("Greek Yogurt 16 oz", t.name)
        assertEquals("3.49", t.price!!.amount.toPlain())
        assertEquals("4.99", t.regularPrice!!.toPlain())
    }

    @Test
    fun georgianBuyWordsAreDealWording() {
        val t = tag("GEL", l("შეიძინეთ ახლა", 60, 20, 900, 120), l("ლიმონათი", 80, 140, 500, 190), l("2.49 ₾", 150, 220, 600, 420))
        assertEquals("ლიმონათი", t.name)
    }
}

/** Of two close candidates, the one nearer the price, then the longer (design §6.2). */
class NameTieBreakTest {
    @Test
    fun theNearerOfTwoCloseLines() {
        // Scored within a whisker (the upper one is longer, the lower one nearer): the nearer.
        val t = tag("USD", l("Organic oat drink", 80, 20, 520, 70), l("Barista blend", 80, 120, 420, 150), l("\$3.99", 150, 190, 600, 390))
        assertEquals("Barista blend", t.name)
    }
}

/** Lines printed on tag after tag are the store's (design §6.2). */
class StorePhrasesTest {
    private val slogan = l("Nikora ყოველთვის შენთან", 60, 20, 900, 110)

    private fun learnt(): Set<String> {
        val phrases = StorePhrases()
        phrases.record("2.49GEL", listOf(slogan, l("ლიმონათი", 0, 0, 10, 10)))
        phrases.record("4.99GEL", listOf(slogan, l("ყველი", 0, 0, 10, 10)))
        assertFalse(StorePhrases.phraseOf(slogan.text) in phrases.known)
        phrases.record("4.99GEL", listOf(slogan)) // the same tag again doesn't count twice
        assertFalse(StorePhrases.phraseOf(slogan.text) in phrases.known)
        phrases.record("12.99GEL", listOf(slogan, l("პური", 0, 0, 10, 10)))
        return phrases.known
    }

    @Test
    fun aLineOnThreeTagsIsTheStores() {
        val known = learnt()
        assertEquals(setOf(StorePhrases.phraseOf(slogan.text)), known)
        // Printed big above the name, it would have won; now the product's name does.
        val lines = arrayOf(slogan, l("ქოქოსის რძე", 80, 130, 600, 170), l("6.49 ₾", 150, 210, 600, 400))
        assertEquals("Nikora ყოველთვის შენთან", tag("GEL", *lines).name)
        assertEquals("ქოქოსის რძე", tag("GEL", *lines, phrases = known).name)
    }

    @Test
    fun aTagWithNothingElseIsStillNamed() {
        // A brand line seen on several sizes of one product still names a tag that has nothing else.
        val t = tag("GEL", slogan, l("6.49 ₾", 150, 210, 600, 400), phrases = learnt())
        assertEquals("Nikora ყოველთვის შენთან", t.name)
    }

    @Test
    fun phrasesIgnoreNumbersAndCase() {
        assertEquals(StorePhrases.phraseOf("Celebra tus ahorros!"), StorePhrases.phraseOf("CELEBRA TUS AHORROS 2026"))
        assertNotEquals(StorePhrases.phraseOf("Celebra tus ahorros"), StorePhrases.phraseOf("Leche entera"))
    }
}
