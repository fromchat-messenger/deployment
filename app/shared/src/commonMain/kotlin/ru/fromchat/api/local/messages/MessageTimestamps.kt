package ru.fromchat.api.local.messages

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val OFFSET_SUFFIX = Regex("""[+-]\d{2}:\d{2}$""")

/** ISO-8601 UTC instant for new/queued messages. */
fun nowMessageTimestampIso(): String = Clock.System.now().toString()

/**
 * Parse message timestamps from server or client.
 *
 * - Strings with `Z` / an offset (optimistic client stamps, proper UTC) are true instants.
 * - Zone-less ISO from the API is naive server wall time (`datetime.now().isoformat()`),
 *   interpreted in the device zone so HH:mm matches the user's clock.
 */
internal fun parseMessageInstant(timestamp: String): Instant? {
    val raw = timestamp.trim()
    if (raw.isEmpty()) return null
    val normalized = raw.replace(' ', 'T')
    if (hasExplicitOffset(normalized)) {
        return parseInstantOrNull(normalized)
    }
    val local = parseLocalDateTimeOrNull(normalized) ?: return null
    return runCatching {
        local.toInstant(TimeZone.currentSystemDefault())
    }.getOrNull()
}

internal fun parseMessageTimestampMillis(timestamp: String): Long? =
    parseMessageInstant(timestamp)?.toEpochMilliseconds()

/** HH:mm today; date + time when the message is from another day (bubble footer). */
internal fun formatMessageBubbleTimeLocal(timestamp: String): String {
    val local = parseMessageInstant(timestamp)?.toDeviceLocal() ?: return ""
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    val time = "$hour:$minute"
    if (local.date == now.date) return time
    val day = local.day.toString().padStart(2, '0')
    val month = local.month.number.toString().padStart(2, '0')
    return if (local.year == now.year) {
        "$day.$month $time"
    } else {
        "$day.$month.${local.year} $time"
    }
}

/** HH:mm in the device time zone (bubble footer). */
internal fun formatMessageTimeLocal(timestamp: String): String {
    val local = parseMessageInstant(timestamp)?.toDeviceLocal() ?: return ""
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

/** MM/dd/yyyy HH:mm in the device time zone (fullscreen header). */
internal fun formatMessageDateTimeLocal(timestamp: String): String {
    val local = parseMessageInstant(timestamp)?.toDeviceLocal() ?: return timestamp.trim()
    val month = local.month.number.toString().padStart(2, '0')
    val day = local.day.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$month/$day/${local.year} $hour:$minute"
}

/**
 * Chat date separator label: Today / Yesterday / "d MMMM" / "d MMMM yyyy".
 */
internal fun formatChatDateSeparator(
    date: LocalDate,
    todayLabel: String,
    yesterdayLabel: String,
    monthName: (Int) -> String,
): String {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)
    return when (date) {
        today -> todayLabel
        yesterday -> yesterdayLabel
        else -> {
            val month = monthName(date.month.number)
            if (date.year == today.year) {
                "${date.day} $month"
            } else {
                "${date.day} $month ${date.year}"
            }
        }
    }
}

private fun parseInstantOrNull(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()

private fun parseLocalDateTimeOrNull(value: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(value) }.getOrNull()

private fun hasExplicitOffset(value: String): Boolean =
    value.endsWith('Z', ignoreCase = true) || OFFSET_SUFFIX.containsMatchIn(value)

private fun Instant.toDeviceLocal(): LocalDateTime =
    toLocalDateTime(TimeZone.currentSystemDefault())
