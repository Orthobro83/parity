package app.parity.core.fx

import app.parity.core.money.CurrencyCode
import app.parity.core.money.MINUS
import app.parity.core.money.MoneyMath
import app.parity.core.money.divideMoney
import app.parity.core.money.toFixed
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * An exchange rate: 1 [base] = [value] [quote].
 * In Parity the base is the user's currency and the quote is the local (shelf) currency,
 * so [value] is "local units per 1 base unit" (e.g. 2.74 GEL per USD). This is `R` in design §9.
 */
data class FxRate(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val value: BigDecimal,
    /** When the provider published this rate (epoch ms), if it says. */
    val publishedAtMs: Long?,
    /** When Parity fetched it (epoch ms). */
    val fetchedAtMs: Long,
    val provider: String,
    /** Intermediate currency used for a cross rate, e.g. "USD" for BTC→USD→GEL. */
    val pivot: CurrencyCode? = null,
) {
    fun inverted(): FxRate = copy(base = quote, quote = base, value = BigDecimal.ONE.divideMoney(value))

    /** Converts a local-currency amount into the base currency. */
    fun localToBase(local: BigDecimal): BigDecimal = local.divideMoney(value)

    fun baseToLocal(base: BigDecimal): BigDecimal = base.multiply(value, MoneyMath)
}

enum class FxDirection { UP, DOWN, SAME }

/**
 * The ▲/▼ indicator from design §9. No dead-band: any non-zero change in R shows a direction.
 *
 * UP   — R rose: each unit of the user's currency buys more local currency (local weakened).
 * DOWN — R fell: the user's currency buys less (local strengthened).
 */
data class FxIndicator(
    val direction: FxDirection,
    /** R_now / R_prev − 1, full precision. */
    val change: BigDecimal,
    val previousRate: BigDecimal,
    val currentRate: BigDecimal,
) {
    /** Percentage for the badge with at least 3 significant digits: "+0.0142 %", "−0.387 %", "+2.21 %". */
    val badgeText: String get() = formatPercent(change, significantDigits = 3)

    companion object {
        fun compute(previousRate: BigDecimal, currentRate: BigDecimal): FxIndicator {
            require(previousRate.signum() > 0 && currentRate.signum() > 0) { "Rates must be positive" }
            val change = currentRate.divideMoney(previousRate) - BigDecimal.ONE
            val direction = when (change.signum()) {
                1 -> FxDirection.UP
                -1 -> FxDirection.DOWN
                else -> FxDirection.SAME
            }
            return FxIndicator(direction, change, previousRate, currentRate)
        }
    }
}

/**
 * Formats a fraction (0.0221 = 2.21 %) as a signed percentage with at least [significantDigits]
 * significant digits and never fewer than 2 decimals for small values. Zero is "0 %".
 */
fun formatPercent(fraction: BigDecimal, significantDigits: Int = 3): String {
    if (fraction.isZero()) return "0 %"
    val pct = fraction.multiply(BigDecimal.fromInt(100), MoneyMath)
    val magnitude = pct.abs()
    // Position of the most significant digit: 2.21 → 0, 0.387 → -1, 0.0142 → -2, 12.3 → 1.
    val leading = leadingDigitExponent(magnitude)
    val decimals = maxOf(0, significantDigits - 1 - leading)
    val sign = if (pct.signum() > 0) "+" else MINUS
    return "$sign${magnitude.toFixed(decimals)} %"
}

/** Full-precision percentage for the detail popover, e.g. "+2.2388059701 %". */
fun formatPercentPrecise(fraction: BigDecimal, decimals: Int = 10): String {
    if (fraction.isZero()) return "0 %"
    val pct = fraction.multiply(BigDecimal.fromInt(100), MoneyMath)
    val sign = if (pct.signum() > 0) "+" else MINUS
    val text = pct.abs().toFixed(decimals).trimEnd('0').trimEnd('.')
    return "$sign$text %"
}

/** A rate for display: at most [significantDigits] significant digits, no trailing zeros. */
fun formatRate(value: BigDecimal, significantDigits: Int = 8): String = roundSignificant(value, significantDigits).toPlainString()

/** Rounds to [digits] significant digits; used so cross rates don't carry false precision. */
fun roundSignificant(value: BigDecimal, digits: Int): BigDecimal =
    value.divide(BigDecimal.ONE, com.ionspin.kotlin.bignum.decimal.DecimalMode(digits.toLong(), com.ionspin.kotlin.bignum.decimal.RoundingMode.ROUND_HALF_AWAY_FROM_ZERO))

private fun BigDecimal.toPlainString(): String {
    val s = toStringExpanded()
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

private fun leadingDigitExponent(positive: BigDecimal): Int {
    val s = positive.toStringExpanded()
    val intPart = s.substringBefore('.')
    if (intPart.trimStart('0').isNotEmpty()) return intPart.trimStart('0').length - 1
    val frac = s.substringAfter('.', "")
    val zeros = frac.takeWhile { it == '0' }.length
    return -(zeros + 1)
}
