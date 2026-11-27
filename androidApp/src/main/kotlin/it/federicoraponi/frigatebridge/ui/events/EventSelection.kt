package it.federicoraponi.frigatebridge.ui.events

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.ui.EventsState
import it.federicoraponi.frigatebridge.ui.MAXIMUM_SHARED_MOMENTS
import it.federicoraponi.frigatebridge.ui.SelectionShare
import it.federicoraponi.frigatebridge.ui.designsystem.ConfirmSheet
import it.federicoraponi.frigatebridge.ui.designsystem.LockableSheet
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.SheetTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.StatusText
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.designsystem.adminFailureText
import it.federicoraponi.frigatebridge.ui.designsystem.failureText
import it.federicoraponi.frigatebridge.ui.recordings.rememberExportSharing

/** What can be asked about the chosen moments. */
data class SelectionActions(
    val toggle: (ReviewItem) -> Unit = {},
    val all: () -> Unit = {},
    val clear: () -> Unit = {},
    val markViewed: () -> Unit = {},
    val delete: () -> Unit = {},
    val dismissDeleteFailure: () -> Unit = {},
    val share: () -> Unit = {},
    val endSharing: (shared: Boolean) -> Unit = {},
)

/** Takes the place of the top bar while moments are being chosen. */
@Composable
internal fun SelectionTopBar(
    state: EventsState,
    actions: SelectionActions,
) {
    TopAppBar(
        title = {
            ScreenTitle(
                pluralStringResource(R.plurals.events_selected, state.selected.size, state.selected.size),
            )
        },
        navigationIcon = {
            IconButton(onClick = actions.clear, shapes = IconButtonDefaults.shapes()) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.events_selection_end))
            }
        },
        actions = {
            if (state.selected.size < state.items.size) {
                TextButton(onClick = actions.all, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.events_select_all))
                }
            }
        },
    )
}

/** What can be done with the chosen moments, floating above the list. */
@Composable
internal fun SelectionToolbar(
    state: EventsState,
    actions: SelectionActions,
) {
    var isConfirming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val tooMany = stringResource(R.string.events_share_limit, MAXIMUM_SHARED_MOMENTS)
    HorizontalFloatingToolbar(expanded = true) {
        ToolbarAction(Symbols.Share, stringResource(R.string.events_share)) {
            if (state.canShareSelection) {
                actions.share()
            } else {
                Toast.makeText(context, tooMany, Toast.LENGTH_LONG).show()
            }
        }
        if (state.items.any { it.id in state.selected && !it.isViewed }) {
            ToolbarAction(Symbols.Check, stringResource(R.string.events_mark_viewed), actions.markViewed)
        }
        // Deleting takes the recordings with it: for administrators, and never of a moment still going on.
        if (state.canManage && state.deletable.isNotEmpty()) {
            ToolbarAction(Symbols.Delete, stringResource(R.string.events_delete)) {
                actions.dismissDeleteFailure()
                isConfirming = true
            }
        }
    }
    if (isConfirming) {
        if (state.deletable.isEmpty()) {
            isConfirming = false
        } else {
            val count = state.deletable.size
            ConfirmSheet(
                title = pluralStringResource(R.plurals.events_delete_title, count, count),
                message = stringResource(R.string.events_delete_message),
                confirmLabel = stringResource(R.string.events_delete_confirm),
                onConfirm = actions.delete,
                onDismiss = {
                    isConfirming = false
                    actions.dismissDeleteFailure()
                },
                running = state.isDeleting,
                error = state.deleteFailure?.let { adminFailureText(it) },
            )
        }
    }
}

@Composable
private fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(label)
    }
}

/** Follows the sharing of the chosen moments, from the server cutting their videos to the other app. */
@Composable
internal fun SelectionSheets(
    state: EventsState,
    actions: SelectionActions,
) {
    val share = state.share
    if (share == SelectionShare.None) return
    val sharing = rememberExportSharing()
    val context = LocalContext.current
    val missing = (share as? SelectionShare.Ready)?.missing ?: 0
    val missingText = pluralStringResource(R.plurals.events_share_missing, missing, missing)
    LaunchedEffect(share) {
        if (share is SelectionShare.Ready) {
            if (missing > 0) Toast.makeText(context, missingText, Toast.LENGTH_LONG).show()
            sharing.shareAll(share.files) { actions.endSharing(true) }
        }
    }
    val cancel = {
        sharing.cancel()
        actions.endSharing(false)
    }
    // Only its own button closes it, so that no work goes on unseen.
    LockableSheet(locked = true, onDismiss = cancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SheetTitle(stringResource(R.string.events_share))
            val problem =
                (share as? SelectionShare.Failed)?.let { failureText(it.failure, R.string.export_failed) }
                    ?: sharing.failure?.let { stringResource(R.string.export_failed) + "\n" + it }
            if (problem != null) {
                NoticeCard(problem, announce = true)
            } else {
                StatusText(
                    if (share is SelectionShare.Preparing) {
                        stringResource(
                            R.string.events_share_preparing,
                            minOf(share.done + 1, share.total),
                            share.total,
                        )
                    } else {
                        stringResource(R.string.export_downloading)
                    },
                )
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = cancel, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (problem != null) R.string.close else R.string.cancel))
            }
        }
    }
}
