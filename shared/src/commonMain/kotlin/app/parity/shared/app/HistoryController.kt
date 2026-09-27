package app.parity.shared.app

import app.parity.shared.data.SessionDetail
import app.parity.shared.data.ShoppingSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** Year / month / day search; any part may be empty (design §11.2). */
data class HistoryFilter(val year: String = "", val month: String = "", val day: String = "") {
    val isEmpty: Boolean get() = year.isBlank() && month.isBlank() && day.isBlank()

    /** Epoch-ms range [from, to] in the phone's time zone, or everything when no year is given. */
    fun range(zone: TimeZone = TimeZone.currentSystemDefault()): Pair<Long, Long> {
        val y = year.trim().toIntOrNull() ?: return 0L to Long.MAX_VALUE
        val m = month.trim().toIntOrNull()?.takeIf { it in 1..12 }
        val d = day.trim().toIntOrNull()?.takeIf { it in 1..31 }
        return runCatching {
            val (start, end) = when {
                m == null -> LocalDate(y, 1, 1) to LocalDate(y + 1, 1, 1)
                d == null -> LocalDate(y, m, 1).let { it to it.plus(1, DateTimeUnit.MONTH) }
                else -> LocalDate(y, m, d).let { it to it.plus(1, DateTimeUnit.DAY) }
            }
            start.atStartOfDayIn(zone).toEpochMilliseconds() to end.atStartOfDayIn(zone).toEpochMilliseconds() - 1
        }.getOrDefault(0L to Long.MAX_VALUE)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryController(private val graph: AppGraph) {
    val filter = MutableStateFlow(HistoryFilter())

    val sessions: StateFlow<List<ShoppingSessionEntity>> = filter
        .flatMapLatest { f -> f.range().let { (from, to) -> graph.shopping.sessionsBetween(from, to) } }
        .stateIn(graph.scope, SharingStarted.Eagerly, emptyList())

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail

    fun open(sessionId: String) {
        graph.scope.launch { _detail.value = graph.shopping.sessionDetail(sessionId) }
    }

    fun close() {
        _detail.value = null
    }
}
