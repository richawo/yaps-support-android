package ai.yaps.support.ui

import ai.yaps.support.internal.core.ChatState
import ai.yaps.support.internal.outbox.OutboxEntry
import ai.yaps.support.internal.outbox.OutboxState
import ai.yaps.support.internal.protocol.AuthorType
import ai.yaps.support.internal.protocol.Message
import java.util.Calendar
import java.util.TimeZone

/** One row of the transcript, newest last. */
internal sealed interface TimelineItem {
    val key: String

    data class DayHeader(val dayStartMillis: Long) : TimelineItem {
        override val key: String get() = "day-$dayStartMillis"
    }

    data class Server(
        val message: Message,
        val firstInRun: Boolean,
        val lastInRun: Boolean,
        /** Status line under the latest customer message only (Reception groups status labels). */
        val status: OutgoingStatus?,
        val millis: Long,
    ) : TimelineItem {
        override val key: String get() = message.clientId ?: message.id
    }

    data class Pending(val entry: OutboxEntry, val firstInRun: Boolean, val lastInRun: Boolean, val progress: Float?) : TimelineItem {
        override val key: String get() = entry.clientId
    }
}

internal enum class OutgoingStatus { SENDING, SENT, READ, FAILED }

internal fun OutboxEntry.outgoingStatus(): OutgoingStatus =
    if (state == OutboxState.FAILED) OutgoingStatus.FAILED else OutgoingStatus.SENDING

/** Keyed by clientId when known so a bubble keeps its identity from "Sending…" to "Sent". */
internal fun buildTimeline(state: ChatState): List<TimelineItem> {
    val serverIds = state.messages.mapNotNull { it.clientId }.toHashSet()
    val pending = state.pending.filter { it.clientId !in serverIds }.sortedBy { it.createdAt }
    val lastCustomerSeq = state.messages.lastOrNull { it.author.type == AuthorType.CUSTOMER }?.seq

    data class Row(val authorKey: String, val millis: Long, val make: (Boolean, Boolean) -> TimelineItem)

    val rows = ArrayList<Row>(state.messages.size + pending.size)
    for (m in state.messages) {
        val millis = parseIsoMillis(m.createdAt) ?: 0L
        val status = if (m.author.type == AuthorType.CUSTOMER && m.seq == lastCustomerSeq && pending.isEmpty()) {
            if (state.agentReadSeq >= m.seq) OutgoingStatus.READ else OutgoingStatus.SENT
        } else null
        rows += Row(authorKey(m), millis) { first, last -> TimelineItem.Server(m, first, last, status, millis) }
    }
    for (p in pending) {
        rows += Row(AuthorType.CUSTOMER, p.createdAt) { first, last -> TimelineItem.Pending(p, first, last, state.uploadProgress[p.clientId]) }
    }

    val out = ArrayList<TimelineItem>(rows.size + 4)
    var lastDay = Long.MIN_VALUE
    rows.forEachIndexed { i, row ->
        val day = if (row.millis > 0) startOfDay(row.millis) else lastDay
        val newDay = day != lastDay && row.millis > 0
        if (newDay) {
            out += TimelineItem.DayHeader(day)
            lastDay = day
        }
        val prev = rows.getOrNull(i - 1)
        val next = rows.getOrNull(i + 1)
        val first = newDay || prev == null || prev.authorKey != row.authorKey || row.millis - prev.millis > RUN_GAP_MS
        val last = next == null || next.authorKey != row.authorKey || next.millis - row.millis > RUN_GAP_MS ||
            (next.millis > 0 && startOfDay(next.millis) != day)
        out += row.make(first, last)
    }
    return out
}

private const val RUN_GAP_MS = 5 * 60_000L

private fun authorKey(m: Message): String = when (m.author.type) {
    AuthorType.CUSTOMER -> AuthorType.CUSTOMER
    else -> "${m.author.type}:${m.author.id ?: m.author.name ?: ""}"
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private val isoPattern = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})?$""")

/** ISO 8601 → epoch millis without java.time (minSdk 24 has no desugaring here). */
internal fun parseIsoMillis(value: String): Long? {
    val m = isoPattern.matchEntire(value.trim()) ?: return null
    val g = m.groupValues
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
    }
    val fraction = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
    var millis = cal.timeInMillis + fraction
    val zone = g[8]
    if (zone.isNotEmpty() && zone != "Z") {
        val sign = if (zone[0] == '-') -1 else 1
        val digits = zone.substring(1).replace(":", "")
        val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
        millis -= sign * offset
    }
    return millis
}
