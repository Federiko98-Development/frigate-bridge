package it.federicoraponi.frigatebridge.ui.events

import android.text.format.DateFormat
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.recordings.DayPickerDialog
import java.time.LocalDate

// Further back than any server keeps its recordings.
private const val YEARS_BACK = 5

// From this font scale on, the clock face is too cramped: the time is typed instead, unless the user switches.
private const val LARGE_FONT_SCALE = 1.5f

/** Asks for a day up to [today], then for the time from which to show it, or the whole of it. */
@Composable
fun MomentPicker(
    date: String,
    today: String,
    fromMinute: Int?,
    onDismiss: () -> Unit,
    onPick: (date: String, fromMinute: Int?) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val day = chosen
    if (day == null) {
        val year = LocalDate.parse(today).year
        DayPickerDialog(
            selected = date,
            years = year - YEARS_BACK..year,
            // Dates written this way sort like the days they name.
            isSelectable = { it <= today },
            onDismiss = onDismiss,
            onSelect = { chosen = it },
        )
    } else {
        val largeFont = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
        var typing by rememberSaveable { mutableStateOf(largeFont) }
        val time =
            rememberTimePickerState(
                initialHour = (fromMinute ?: 0) / 60,
                initialMinute = (fromMinute ?: 0) % 60,
                is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            )
        TimePickerDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.events_time_title)) },
            // Typing the time also suits screen readers and keyboards better than the clock face.
            modeToggleButton = {
                TimePickerDialogDefaults.DisplayModeToggle(
                    onDisplayModeChange = { typing = !typing },
                    displayMode = if (typing) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onPick(day, time.hour * 60 + time.minute) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.events_from_time)) }
            },
            dismissButton = {
                TextButton(onClick = { onPick(day, null) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.events_whole_day))
                }
            },
        ) {
            if (typing) TimeInput(state = time) else TimePicker(state = time)
        }
    }
}
