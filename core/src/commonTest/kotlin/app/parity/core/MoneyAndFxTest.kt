package app.parity.core

import app.parity.core.fx.FxDirection
import app.parity.core.fx.FxIndicator
import app.parity.core.fx.FxRate
import app.parity.core.fx.formatPercent
import app.parity.core.money.Countries
import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyFormat
import app.parity.core.money.decimal
import app.parity.core.money.parseDecimal
import app.parity.core.money.toFixed
import app.parity.core.money.toPlain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyAndFxTest {
    @Test
    fun parsesPlainAndScientificDecimals() {
        assertEquals("12.5", decimal("12.5").toPlain())
        assertEquals("0.000011919995", decimal("1.1919995e-05").toPlain())
        assertEquals("1000", decimal("1E+3").toPlain())
        assertEquals("-0.25", decimal("-.25").toPlain())
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal("1.2.3"))
    }

    @Test
    fun fixedDecimalsPadAndRound() {
        assertEquals("3.70", decimal("3.7").toFixed(2))
        assertEquals("3.71", decimal("3.705").toFixed(2))
        assertEquals("0.00004410", decimal("0.000044099").toFixed(8))
        assertEquals("1235", decimal("1234.5").toFixed(0))
        assertEquals("0.00", decimal("-0.001").toFixed(2))
    }

    @Test
    fun formatsMoneyWithSymbolsAndGrouping() {
        assertEquals("$3.71", MoneyFormat.format(decimal("3.705"), CurrencyCode.USD))
        assertEquals("₾1,234.50", MoneyFormat.format(decimal("1234.5"), CurrencyCode.GEL))
        assertEquals("₿0.00004410", MoneyFormat.format(decimal("0.0000441"), CurrencyCode.BTC))
        assertEquals("CHF 12.50", MoneyFormat.format(decimal("12.5"), CurrencyCode("CHF")))
        assertEquals("¥1,500", MoneyFormat.format(decimal("1500"), CurrencyCode("JPY")))
        assertEquals("ZEC 0.00241000", MoneyFormat.format(decimal("0.00241"), CurrencyCode("ZEC")))
    }

    @Test
    fun catalogCoversUserCurrenciesAndCountries() {
        listOf("USD", "CAD", "GEL", "BTC", "ZEC").forEach { assertTrue(Currencies.isKnown(CurrencyCode(it)), it) }
        assertEquals(CurrencyCode.GEL, Countries.currencyFor("ge"))
        assertEquals(CurrencyCode.EUR, Countries.currencyFor("BG"))
        assertEquals("🇬🇪", Countries.flag("GE"))
    }

    @Test
    fun convertsLocalToBase() {
        val rate = FxRate(CurrencyCode.USD, CurrencyCode.GEL, decimal("2.6045"), null, 0, "test")
        assertEquals("3.84", rate.localToBase(decimal("9.99")).toFixed(2))
        assertEquals("9.99", rate.baseToLocal(rate.localToBase(decimal("9.99"))).toFixed(2))
    }

    @Test
    fun indicatorShowsAnyNonZeroChange() {
        val up = FxIndicator.compute(decimal("2.68"), decimal("2.74"))
        assertEquals(FxDirection.UP, up.direction)
        assertEquals("+2.24 %", up.badgeText)

        val tinyDown = FxIndicator.compute(decimal("2.604500"), decimal("2.604496"))
        assertEquals(FxDirection.DOWN, tinyDown.direction)
        assertEquals("−0.000154 %", tinyDown.badgeText)

        val same = FxIndicator.compute(decimal("2.6045"), decimal("2.60450"))
        assertEquals(FxDirection.SAME, same.direction)
        assertEquals("0 %", same.badgeText)
    }

    @Test
    fun percentKeepsThreeSignificantDigits() {
        assertEquals("+0.0142 %", formatPercent(decimal("0.000142")))
        assertEquals("−0.387 %", formatPercent(decimal("-0.00387")))
        assertEquals("+12.3 %", formatPercent(decimal("0.1234")))
        assertEquals("+123 %", formatPercent(decimal("1.234")))
    }
}
