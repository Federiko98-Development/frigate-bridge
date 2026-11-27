package it.federicoraponi.watchdeck.ui.recordings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// The date picker speaks of days as their midnight in UTC, whatever the phone's time zone.
private fun pickerMillis(date: String): Long =
    LocalDate
        .parse(date)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

private fun pickerDate(millis: Long): String =
    Instant
        .ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toString()

/**
 * The day on screen; tapping it opens a calendar where only the [available] days, written as `yyyy-mm-dd`, can be
 * chosen.
 */
@Composable
fun DaySelector(
    selected: String,
    available: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.day_choose)
    FilledTonalButton(onClick = { picking = true }, shapes = ButtonDefaults.shapes(), modifier = modifier) {
        Icon(Symbols.DateRange, contentDescription = description)
        Text(dayLabel(selected), modifier = Modifier.padding(start = Spacing.sm))
    }
    if (picking) {
        val days = available.toSet()
        val years = (available + selected).map { LocalDate.parse(it).year }
        DayPickerDialog(
            selected = selected,
            years = years.min()..years.max(),
            isSelectable = { it in days },
            onDismiss = { picking = false },
            onSelect = {
                picking = false
                onSelect(it)
            },
        )
    }
}

/** A calendar to choose a day among those [isSelectable] accepts, written as `yyyy-mm-dd`. */
@Composable
fun DayPickerDialog(
    selected: String,
    years: IntRange,
    isSelectable: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = pickerMillis(selected),
            yearRange = years,
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = isSelectable(pickerDate(utcTimeMillis))

                    override fun isSelectableYear(year: Int) = year in years
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onSelect(pickerDate(it)) } },
                enabled = state.selectedDateMillis != null,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.day_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}
