package app.parity.core.scan

import app.parity.core.money.decimal
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** A number found inside a line of text. [start]/[end] index into the line text. */
data class NumberRun(
    val raw: String,
    val start: Int,
    val end: Int,
    val amount: BigDecimal,
    /** Digits after the decimal separator (0 when the number is an integer). */
    val decimals: Int,
)

/**
 * Finds numbers such as "9.99", "9,99", "1 299,00", "1,299.00", "1'299.00" and "9,-" in text and
 * decides which separator is the decimal point (design §6.1).
 */
object AmountParser {
    private const val GROUPING_CHARS = "   '’"

    fun findNumbers(text: String, localDecimals: Int = 2): List<NumberRun> {
        val runs = mutableListOf<NumberRun>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isAsciiDigit() || (i > 0 && text[i - 1].isAsciiDigit())) {
                i++
                continue
            }
            val start = i
            var end = i
            var separatorsSoFar = 0
            while (end < text.length) {
                val c = text[end]
                if (c.isAsciiDigit()) {
                    end++
                } else if (isSeparator(c) && end + 1 < text.length && text[end + 1].isAsciiDigit()) {
                    if (c in GROUPING_CHARS) {
                        // A space only groups thousands ("1 299") or styles cents ("3 99");
                        // otherwise it separates two numbers ("12 5").
                        val following = digitsFrom(text, end + 1)
                        val cents = following == 2 && separatorsSoFar == 0 && end - start <= 4
                        if (following != 3 && !cents) break
                    }
                    separatorsSoFar++
                    end++
                } else {
                    break
                }
            }
            var raw = text.substring(start, end)
            var consumedEnd = end
            // "9,-" / "9.–" means a whole amount (common in DE/AT/CH/Scandinavia).
            if (end + 1 < text.length && (text[end] == ',' || text[end] == '.') && text[end + 1] in "-–—") {
                consumedEnd = end + 2
                raw = raw + text[end] + "00"
            }
            interpret(raw, localDecimals)?.let { (amount, decimals) ->
                runs += NumberRun(text.substring(start, consumedEnd), start, consumedEnd, amount, decimals)
            }
            i = consumedEnd
        }
        return runs
    }

    private fun digitsFrom(text: String, index: Int): Int {
        var k = 0
        while (index + k < text.length && text[index + k].isAsciiDigit()) k++
        return k
    }

    private fun isSeparator(c: Char) = c == '.' || c == ',' || c in GROUPING_CHARS

    /** Returns the amount and its number of decimals, or null if the run is not a plausible number. */
    fun interpret(raw: String, localDecimals: Int = 2): Pair<BigDecimal, Int>? {
        val groups = mutableListOf<String>()
        val seps = mutableListOf<Char>()
        val current = StringBuilder()
        for (c in raw) {
            if (c.isAsciiDigit()) current.append(c) else {
                groups += current.toString(); current.clear(); seps += c
            }
        }
        groups += current.toString()
        if (groups.any { it.isEmpty() }) return null
        if (seps.isEmpty()) return decimal(groups[0]) to 0

        val last = seps.last()
        val lastGroup = groups.last()
        val decimalSep: Char? = when {
            // "1.234,56" or "1,234.56": the last of two different separators is the decimal point.
            seps.toSet().count { it == '.' || it == ',' } == 2 -> last
            last in GROUPING_CHARS -> {
                // "3 99" styled cents: one space followed by exactly two digits.
                if (seps.size == 1 && last == ' ' && lastGroup.length == 2) last else null
            }
            seps.count { it == last } > 1 -> if (lastGroup.length == 3) null else last
            lastGroup.length <= 2 -> last
            lastGroup.length == 3 -> if (localDecimals == 3) last else null
            else -> return null
        }

        val intGroups: List<String>
        val fraction: String
        if (decimalSep != null) {
            intGroups = groups.dropLast(1)
            fraction = lastGroup
        } else {
            intGroups = groups
            fraction = ""
        }
        // Grouped thousands must be 3 digits after the first group.
        if (intGroups.size > 1 && intGroups.drop(1).any { it.length != 3 }) return null
        if (intGroups.size > 1 && intGroups.first().length > 3) return null
        val intDigits = intGroups.joinToString("")
        val value = if (fraction.isEmpty()) intDigits else "$intDigits.$fraction"
        return decimal(value) to fraction.length
    }
}

internal fun Char.isAsciiDigit() = this in '0'..'9'
