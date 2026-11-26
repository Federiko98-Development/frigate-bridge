package it.federicoraponi.frigatebridge.ui.designsystem

import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.PopupProperties

data class MenuEntry(
    val label: String,
    val onClick: () -> Unit,
    val leading: @Composable () -> Unit,
    val trailing: (@Composable () -> Unit)? = null,
)

/** An Expressive menu: one rounded group whose items change shape with their position. */
@Composable
fun ExpressiveMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entries: List<MenuEntry>,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // Focusable, so that the back gesture closes the menu instead of leaving the screen or the app.
        properties = PopupProperties(focusable = true),
    ) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
            entries.forEachIndexed { index, entry ->
                DropdownMenuItem(
                    onClick = {
                        onDismiss()
                        entry.onClick()
                    },
                    text = { Text(entry.label) },
                    shape = MenuDefaults.itemShape(index, entries.size).shape,
                    leadingIcon = entry.leading,
                    trailingContent = entry.trailing,
                )
            }
        }
    }
}
