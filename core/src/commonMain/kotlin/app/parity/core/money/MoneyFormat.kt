package app.parity.core.money

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** Unicode minus, used for display so negative values line up with the plus sign. */
const val MINUS = "−"

object MoneyFormat {
    /**
     * "$3.71", "₾1,234.50", "₿0.00004410", "CHF 12.50".
     * Codes used as symbols (letters only, e.g. "CHF", "ZEC") get a space after them.
     */
    fun format(amount: BigDecimal, currency: CurrencyCode, decimals: Int = Currencies[currency].decimals): String {
        val c = Currencies[currency]
        val fixed = amount.toFixed(decimals)
        val negative = fixed.startsWith("-")
        val digits = group(fixed.removePrefix("-"))
        val separator = if (c.symbol.last().isLetter() && c.symbol.length > 1) " " else ""
        return (if (negative) MINUS else "") + c.symbol + separator + digits
    }

    /** Amount without a symbol, grouped: "1,234.50". */
    fun plain(amount: BigDecimal, decimals: Int): String {
        val fixed = amount.toFixed(decimals)
        val negative = fixed.startsWith("-")
        return (if (negative) MINUS else "") + group(fixed.removePrefix("-"))
    }

    private fun group(unsigned: String): String {
        val intPart = unsigned.substringBefore('.')
        val frac = unsigned.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return if (frac.isEmpty()) grouped else "$grouped.$frac"
    }

    /** Decimals to show for a quantity like 1 or 1.35 (kg). */
    fun quantity(q: BigDecimal): String = q.toPlain()
}
