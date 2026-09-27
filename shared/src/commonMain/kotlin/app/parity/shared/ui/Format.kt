package app.parity.shared.ui

import app.parity.core.money.parseDecimal
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "14 Mar 2026" */
fun formatDate(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.day} ${MONTHS[dt.month.number - 1]} ${dt.year}"
}

/** "14 Mar 2026, 18:05" */
fun formatDateTime(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${formatDate(epochMs)}, ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}

/** "3 h ago", "12 min ago", "2 days ago" */
fun formatAge(epochMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - epochMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** Parses what the user typed: accepts "9,99" and "9.99". Null unless it's a positive number. */
fun parseUserAmount(text: String): BigDecimal? =
    parseDecimal(text.trim().replace(',', '.'))?.takeIf { it.signum() > 0 }
