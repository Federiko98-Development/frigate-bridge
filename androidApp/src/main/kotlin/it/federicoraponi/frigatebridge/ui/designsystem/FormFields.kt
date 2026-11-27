package it.federicoraponi.frigatebridge.ui.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.drop

// Form fields are segmented groups, like lists: `index` and `count` give a field its place in the group. On a
// surface close to their own, such as a bottom sheet, a `containerColor` a few steps above keeps them visible.

/** A one line text field. [error] marks it and says what is wrong. */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    index: Int,
    count: Int,
    keyboard: KeyboardOptions,
    placeholder: Int? = null,
    error: String? = null,
    containerColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        singleLine = true,
        keyboardOptions = keyboard,
        shape = fieldShape(index, count),
        colors = fieldColors(containerColor),
    )
}

/** A hidden text field that starts with [initial] and reports every change. */
@Composable
fun PasswordField(
    label: Int,
    index: Int,
    count: Int,
    onValueChange: (String) -> Unit,
    error: String? = null,
    initial: String = "",
    containerColor: Color = Color.Unspecified,
) {
    // Not saveable on purpose: the password must not end up in the saved instance state.
    val field = remember { TextFieldState(initial) }
    val report by rememberUpdatedState(onValueChange)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.drop(1).collect { report(it) }
    }
    SecureTextField(
        state = field,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        shape = fieldShape(index, count),
        colors = fieldColors(containerColor),
    )
}

/** A line of help under a group, pulled up towards the group it explains. */
@Composable
fun GroupHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
}

@Composable
private fun fieldShape(
    index: Int,
    count: Int,
): Shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape

@Composable
private fun fieldColors(chosen: Color): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val container = chosen.takeOrElse { scheme.surfaceContainer }
    // A focused field is a step brighter. A chosen colour stays: the label and the cursor show the focus.
    val focused = chosen.takeOrElse { scheme.surfaceContainerHigh }
    return TextFieldDefaults.colors(
        focusedContainerColor = focused,
        unfocusedContainerColor = container,
        disabledContainerColor = container,
        errorContainerColor = MaterialTheme.colorScheme.errorContainer,
        // The container separates the fields; no underline is needed.
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )
}
