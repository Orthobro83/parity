package app.parity.core.money

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode

/** Precision used for all intermediate money and rate arithmetic. Never use Double for money. */
val MoneyMath = DecimalMode(decimalPrecision = 34, roundingMode = RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

fun decimal(value: String): BigDecimal = parseDecimal(value) ?: error("Not a decimal: $value")

fun decimal(value: Int): BigDecimal = BigDecimal.fromInt(value)

/** Parses "12.5", "-0.001", "1.1919995e-05" and "1E+3". Returns null for anything else. */
fun parseDecimal(value: String): BigDecimal? {
    val s = value.trim()
    if (s.isEmpty()) return null
    val mantissa: String
    val exponent: Int
    val eIndex = s.indexOfFirst { it == 'e' || it == 'E' }
    if (eIndex >= 0) {
        mantissa = s.substring(0, eIndex)
        exponent = s.substring(eIndex + 1).removePrefix("+").toIntOrNull() ?: return null
    } else {
        mantissa = s
        exponent = 0
    }
    if (!Regex("-?\\d+(\\.\\d+)?|-?\\.\\d+").matches(mantissa)) return null
    val base = BigDecimal.parseString(if (mantissa.startsWith(".")) "0$mantissa" else mantissa.replace("-.", "-0."))
    return if (exponent == 0) base else base.moveDecimalPoint(exponent)
}

/** Plain (non-scientific) string with no trailing zeros, e.g. "0.00001234". Used for storage. */
fun BigDecimal.toPlain(): String {
    val s = toStringExpanded()
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

fun BigDecimal.divideMoney(other: BigDecimal): BigDecimal = divide(other, MoneyMath)

fun BigDecimal.roundTo(decimals: Int): BigDecimal =
    roundToDigitPositionAfterDecimalPoint(decimals.toLong(), RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

/** Fixed number of decimals, padded with zeros: 3.7 with 2 decimals → "3.70". */
fun BigDecimal.toFixed(decimals: Int): String {
    val rounded = roundTo(decimals).toStringExpanded()
    val negative = rounded.startsWith("-")
    val body = rounded.removePrefix("-")
    val intPart = body.substringBefore('.')
    val fracPart = body.substringAfter('.', "")
    val text = if (decimals <= 0) intPart else intPart + "." + fracPart.padEnd(decimals, '0').take(decimals)
    val isZero = text.all { it == '0' || it == '.' }
    return if (negative && !isZero) "-$text" else text
}

fun BigDecimal.isPositive(): Boolean = signum() > 0

fun max(a: BigDecimal, b: BigDecimal): BigDecimal = if (a >= b) a else b
