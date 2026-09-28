package app.parity.core

import app.parity.core.money.Countries
import app.parity.core.money.CurrencyCode
import app.parity.core.money.Languages
import app.parity.core.money.toPlain
import app.parity.core.scan.Box
import app.parity.core.scan.Digits
import app.parity.core.scan.LabelReading
import app.parity.core.scan.MultiBuyOffer
import app.parity.core.scan.MultiBuyOffer.Kind
import app.parity.core.scan.NameText
import app.parity.core.scan.OcrFrame
import app.parity.core.scan.OcrLine
import app.parity.core.scan.PriceTagParser
import app.parity.core.scan.TextScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int) =
    OcrLine(text, Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))

private fun parse(local: String, vararg lines: OcrLine) = PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), CurrencyCode(local))

/** Every country the app knows has a label language that can be read and named (design §14). */
class CoverageTest {
    @Test
    fun everyCountryHasAReadableLabelLanguage() {
        for (country in Countries.allCodes) {
            val language = assertNotNull(Languages.labelLanguageFor(country), "no label language for $country")
            assertNotNull(Languages.find(language), "no name for $language ($country)")
            val readable = LabelReading.fastScript(language) != null || LabelReading.tesseractModel(language) != null
            assertTrue(readable, "no reader for $language ($country)")
            if (LabelReading.tesseractModel(language) != null) {
                assertTrue(LabelReading.hasOwnScript(language), "$language is read by Tesseract but has no script letters")
            }
        }
    }

    @Test
    fun readersByScript() {
        assertEquals(TextScript.JAPANESE, LabelReading.fastScript("ja"))
        assertEquals(TextScript.CHINESE, LabelReading.fastScript("zh"))
        assertEquals(TextScript.KOREAN, LabelReading.fastScript("ko"))
        assertEquals(TextScript.DEVANAGARI, LabelReading.fastScript("ne"))
        assertEquals(TextScript.LATIN, LabelReading.fastScript("de"))
        assertEquals(TextScript.LATIN, LabelReading.fastScript("sr-Latn"))
        assertNull(LabelReading.fastScript("hy"))
        assertEquals("hye+eng", LabelReading.tesseractLanguages("hy"))
        assertEquals("kat+eng", LabelReading.tesseractLanguages("ka"))
        assertNull(LabelReading.tesseractLanguages("ja"))
    }

    @Test
    fun serbianAndBosnianTranslateOfflineThroughCroatian() {
        val packs = setOf("hr", "en", "ka")
        assertEquals("hr", LabelReading.offlineSource("sr", packs))
        assertEquals("hr", LabelReading.offlineSource("sr-Latn", packs))
        assertEquals("hr", LabelReading.offlineSource("bs", packs))
        assertEquals("ka", LabelReading.offlineSource("ka", packs))
        assertNull(LabelReading.offlineSource("hy", packs))
        assertEquals("Mleko 2,8% Imlek", LabelReading.forOfflineTranslation("Млеко 2,8% Имлек", "sr"))
        assertEquals("Džem od šljiva", LabelReading.serbianToLatin("Џем од шљива"))
    }
}

class OtherDigitsTest {
    @Test
    fun digitsBecomeAscii() {
        assertEquals("25.90 ر.س", Digits.normalize("٢٥٫٩٠ ر.س"))
        assertEquals("120,000 ریال", Digits.normalize("۱۲۰٬۰۰۰ ریال"))
        assertEquals("1,280円", Digits.normalize("１，２８０円"))
        assertEquals("59 บาท", Digits.normalize("๕๙ บาท"))
        assertEquals("1000 ကျပ်", Digits.normalize("၁၀၀၀ ကျပ်"))
        assertEquals("25%", Digits.normalize("٢٥٪"))
    }

    @Test
    fun arabicIndicPricesParse() {
        val tag = parse("SAR", l("حليب طازج", 100, 100, 500, 150), l("٢٥٫٩٠ ر.س", 150, 180, 450, 300))
        assertEquals("25.9", tag.price!!.amount.toPlain())
        assertEquals("SAR", tag.price!!.currency?.code)
    }

    @Test
    fun persianDigitsParse() {
        val tag = parse("IRR", l("شیر پرچرب", 100, 100, 500, 150), l("۱۲۰٬۰۰۰ ریال", 150, 180, 450, 300))
        assertEquals("120000", tag.price!!.amount.toPlain())
    }
}

class AsianTagsTest {
    @Test
    fun yenTagWhileSetToEuros() {
        val tag = parse("EUR", l("ニンジン (5)", 200, 100, 600, 160), l("¥2150", 250, 180, 500, 260))
        assertEquals("2150", tag.price!!.amount.toPlain())
        assertEquals("JPY", tag.price!!.currency?.code)
        assertEquals("ニンジン (5)", tag.name)
    }

    @Test
    fun japaneseTaxInclusivePriceIsThePrice() {
        val tag = parse(
            "JPY",
            l("国産 豚肉 こま切れ", 100, 50, 600, 110),
            l("本体価格 980円", 100, 130, 700, 330),
            l("税込 1,058円", 100, 350, 500, 420),
        )
        assertEquals("1058", tag.price!!.amount.toPlain())
    }

    @Test
    fun chineseTwoCharacterNameAndYuan() {
        val tag = parse("CNY", l("牛奶", 100, 100, 300, 160), l("¥12.50", 150, 180, 450, 300))
        assertEquals("12.5", tag.price!!.amount.toPlain())
        assertEquals("CNY", tag.price!!.currency?.code)
        assertEquals("牛奶", tag.name)
        assertEquals("牛奶", NameText.pickLines("牛奶", null))
    }

    @Test
    fun chineseDealsAndDiscounts() {
        val deal = parse("CNY", l("可口可乐 330ml", 100, 50, 500, 100), l("¥3.50", 150, 120, 450, 240), l("买一送一", 150, 260, 450, 300))
        assertEquals(MultiBuyOffer(Kind.FREE, 2, paidUnits = 1), deal.multiBuy)
        val sale = parse("CNY", l("酸奶 8折", 100, 50, 500, 100), l("¥9.90", 150, 120, 450, 240), l("12.40", 150, 260, 300, 300))
        assertTrue(sale.isPromo)
        assertEquals("12.4", sale.regularPrice!!.toPlain())
    }

    @Test
    fun koreanOnePlusOne() {
        val tag = parse("KRW", l("우유 1L", 100, 50, 400, 100), l("2,980원", 150, 120, 500, 240), l("1+1 행사", 150, 260, 400, 300))
        assertEquals("2980", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.FREE, 2, paidUnits = 1), tag.multiBuy)
    }

    @Test
    fun thaiBuyTwoGetOne() {
        val tag = parse("THB", l("นมสด", 100, 50, 400, 100), l("฿45", 150, 120, 400, 240), l("ซื้อ 2 แถม 1", 150, 260, 450, 300))
        assertEquals("45", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.FREE, 3, paidUnits = 2), tag.multiBuy)
    }

    @Test
    fun japaneseSaleWordInsideALine() {
        val tag = parse("JPY", l("本日の特価品", 100, 50, 500, 100), l("398円", 150, 120, 450, 240), l("通常価格 498円", 150, 260, 450, 300))
        assertTrue(tag.isPromo)
        assertEquals("398", tag.price!!.amount.toPlain())
    }
}

class MiddleEastTagsTest {
    @Test
    fun hebrewTwoForTwelve() {
        val tag = parse("ILS", l("חלב 3%", 100, 50, 400, 100), l("₪ 6.90", 150, 120, 450, 240), l("2 ב-12", 150, 260, 350, 300))
        assertEquals("6.9", tag.price!!.amount.toPlain())
        assertEquals(MultiBuyOffer(Kind.BUNDLE, 2, price = "12"), tag.multiBuy)
    }

    @Test
    fun dirhamNeedsTheLocalCurrency() {
        // "DH" alone is only trusted where it's the local currency.
        assertEquals("MAD", parse("MAD", l("Lait", 100, 50, 300, 100), l("7.50 DH", 150, 120, 450, 240)).price!!.currency?.code)
        assertNull(parse("EUR", l("Lait", 100, 50, 300, 100), l("7.50 DH", 150, 120, 450, 240)).price!!.currency)
    }
}

/** Real MyMemory answers (2026-09-27) and what should be picked from them. */
class TranslationPickTest {
    private fun tm(text: String, segment: String, quality: Int, match: Double) =
        app.parity.core.scan.TranslationCandidate(text, segment, quality, match, machine = false)

    private fun mt(text: String, segment: String) = app.parity.core.scan.TranslationCandidate(text, segment, 70, 0.85, machine = true)

    private fun best(source: String, language: String, vararg candidates: app.parity.core.scan.TranslationCandidate) =
        app.parity.core.scan.TranslationPick.rank(source, language, candidates.toList()).firstOrNull()

    @Test
    fun machineTranslationWins() {
        assertEquals("Carrots (5)", best("ニンジン (5)", "ja", tm("THE CARROTS! 5", "ニンジン！5", 0, 0.92), mt("Carrots (5)", "ニンジン (5)")))
        assertEquals("Fresh milk", best("حليب طازج", "ar", tm("MILK FRESH", "حليب طازج", 74, 0.98), mt("Fresh milk", "حليب طازج")))
    }

    @Test
    fun agreeingAnswersBeatAStrayOne() {
        assertEquals("cow's milk", best("牛奶", "zh", tm("Susu", "牛奶", 74, 1.0), tm("cow&apos;s milk", "牛奶", 0, 1.0), tm("Milk", "牛奶", 0, 0.99)))
        assertEquals("Fresh milk", best("นมสด", "th", tm("Whole milk", "นมสด", 0, 0.99), tm("Fresh milk", "นมสด", 74, 0.98)))
    }

    @Test
    fun untranslatedAndDictionaryAnswersAreDropped() {
        assertEquals(
            "Carrot",
            best("ニンジン", "ja", tm("[にんじん] /carrot/Daucus carota/", "ニンジン", 0, 1.0), tm("Carrot", "ニンジン", 80, 0.99), tm("Carrot", "ニンジン", 74, 0.99)),
        )
        assertEquals("Milk", best("කිරි", "si", tm("කිරි කපනව", "කිරි", 100, 0.99), tm("Milk", "කිරි", 74, 0.99), tm("Milk", "කිරි", 74, 0.98)))
        assertNull(best("ქოქოსის რძე", "ka", tm("ქოქოსის რძე", "ქოქოსის რძე", 70, 1.0)))
    }

    @Test
    fun answersForOtherTextAreDropped() {
        assertEquals("Milk", best("Сүү", "mn", tm("Milk", "Сүү", 0, 0.99), tm("pasteurized MILK", "пастержүүлсэн СҮҮ", 74, 0.62)))
    }
}

class ZeroDecimalCurrencyTest {
    @Test
    fun aCountInBracketsIsNotThePrice() {
        // The phone report: the name's "(5)" is printed larger than the price.
        val tag = PriceTagParser.parse(OcrFrame(listOf(l("ニンジン (5)", 200, 100, 700, 220), l("¥2150", 250, 240, 520, 320)), 1000, 1000), CurrencyCode("JPY"), strict = true)
        assertEquals("2150", tag.price!!.amount.toPlain())
        assertEquals("ニンジン (5)", tag.name)
    }

    @Test
    fun bareNumbersNeedAMarkWhereThereAreNoDecimals() {
        val calendar = PriceTagParser.parse(OcrFrame(listOf(l("9月", 100, 100, 300, 160), l("2026", 100, 200, 500, 400)), 1000, 1000), CurrencyCode("JPY"), strict = true)
        assertNull(calendar.price)
        val grouped = PriceTagParser.parse(OcrFrame(listOf(l("우유 1L", 100, 100, 300, 160), l("2,980", 100, 200, 500, 400)), 1000, 1000), CurrencyCode("KRW"), strict = true)
        assertEquals("2980", grouped.price!!.amount.toPlain())
    }
}

/** Deal banners and slogans are not names (a real-world report from a Spanish-language store). */
class AdvertisingIsNotANameTest {
    private fun tag(vararg lines: OcrLine) = PriceTagParser.parse(OcrFrame(lines.toList(), 1000, 1000), CurrencyCode("MXN"))

    @Test
    fun bannerAboveTheName() {
        val t = tag(
            l("Super Discount", 60, 40, 900, 160),
            l("Leche Entera Lala 1 L", 80, 190, 700, 240),
            l("$27.50", 150, 280, 600, 480),
        )
        assertEquals("Leche Entera Lala 1 L", t.name)
        assertTrue(t.isPromo)
    }

    @Test
    fun ofertaBannerAndOldPrice() {
        val t = tag(
            l("¡OFERTA!", 60, 30, 700, 150),
            l("Aceite de Oliva Carbonell 500 ml", 80, 180, 900, 230),
            l("$89.90", 150, 270, 600, 470),
            l("Antes $119.00", 150, 490, 500, 540),
        )
        assertEquals("Aceite de Oliva Carbonell 500 ml", t.name)
        assertEquals("89.9", t.price!!.amount.toPlain())
        assertEquals("119", t.regularPrice!!.toPlain())
    }

    @Test
    fun sloganBelowThePrice() {
        val t = tag(
            l("Galletas Marías Gamesa", 80, 60, 800, 110),
            l("$32.00", 150, 150, 600, 350),
            l("Celebra tus ahorros", 80, 380, 900, 450),
        )
        assertEquals("Galletas Marías Gamesa", t.name)
    }

    @Test
    fun onlyAdvertisingMeansNoName() {
        val t = tag(l("Precio bajo todos los días", 60, 40, 900, 150), l("$45.00", 150, 200, 600, 400))
        assertNull(t.name)
    }

    @Test
    fun namesWithMarketingWordsAndAQuantityStay() {
        val t = tag(l("OFERTA", 60, 20, 500, 120), l("Super Bock 6 x 330 ml", 80, 140, 800, 190), l("$159.00", 150, 230, 600, 430))
        assertEquals("Super Bock 6 x 330 ml", t.name)
    }
}

class OtherLabelLanguagesTest {
    @Test
    fun countriesWithSeveralLabelLanguages() {
        assertTrue(Languages.isOtherLabelLanguage("CH", "fr"))
        assertTrue(Languages.isOtherLabelLanguage("CA", "fr"))
        kotlin.test.assertFalse(Languages.isOtherLabelLanguage("MX", "pt"))
        kotlin.test.assertFalse(Languages.isOtherLabelLanguage("ES", "it"))
    }
}
