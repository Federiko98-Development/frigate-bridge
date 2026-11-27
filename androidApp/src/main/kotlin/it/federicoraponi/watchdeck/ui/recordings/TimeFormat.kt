package it.federicoraponi.watchdeck.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import it.federicoraponi.watchdeck.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The time of day of [epochSeconds] on this phone, with seconds. */
@Composable
fun clockTime(epochSeconds: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter
        .ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
}

/** "Today", "Yesterday" or the date, for a day written as `yyyy-mm-dd`. */
@Composable
fun dayLabel(date: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val day = LocalDate.parse(date)
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> DateTimeFormatter.ofPattern("d MMM", locale).format(day)
    }
}

/** "Today · 18:05:09", or the date and the time of [epochSeconds]. */
@Composable
fun dayAndTime(epochSeconds: Long): String = "${dayLabel(dayOf(epochSeconds))} · ${clockTime(epochSeconds)}"

/** The day of [epochSeconds] on this phone, as `yyyy-mm-dd`. */
fun dayOf(epochSeconds: Long): String =
    Instant
        .ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/** A time of day given as [minuteOfDay] minutes after midnight, as the phone writes it. */
@Composable
fun minuteLabel(minuteOfDay: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
}
