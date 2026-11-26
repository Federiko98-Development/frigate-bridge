package it.federicoraponi.frigatebridge.recordings

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.ExperimentalTime

/** A calendar day with recordings. [date] is `yyyy-mm-dd`; [start] and [end] are its local midnights. */
data class RecordingDay(
    val date: String,
    val start: Long,
    val end: Long,
)

/** The day [date] in the time zone [zoneId], or `null` if [date] cannot be read. */
@OptIn(ExperimentalTime::class)
fun recordingDay(
    date: String,
    zoneId: String,
): RecordingDay? {
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
    val zone = TimeZone.of(zoneId)
    // Not always 24 hours: the clocks change twice a year.
    val next = day.plus(DatePeriod(days = 1))
    return RecordingDay(date, day.atStartOfDayIn(zone).epochSeconds, next.atStartOfDayIn(zone).epochSeconds)
}
