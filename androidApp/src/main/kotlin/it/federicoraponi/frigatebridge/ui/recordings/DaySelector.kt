package it.federicoraponi.frigatebridge.ui.recordings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
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
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
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
        Icon(Icons.Filled.DateRange, contentDescription = description)
        Text(dayLabel(selected), modifier = Modifier.padding(start = Spacing.sm))
    }
    if (picking) {
        DayPickerDialog(
            selected = selected,
            available = available,
            onDismiss = { picking = false },
            onSelect = {
                picking = false
                onSelect(it)
            },
        )
    }
}

@Composable
private fun DayPickerDialog(
    selected: String,
    available: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val days = available.toSet()
    val years = available.map { LocalDate.parse(it).year } + LocalDate.parse(selected).year
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = pickerMillis(selected),
            yearRange = years.min()..years.max(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = pickerDate(utcTimeMillis) in days

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
