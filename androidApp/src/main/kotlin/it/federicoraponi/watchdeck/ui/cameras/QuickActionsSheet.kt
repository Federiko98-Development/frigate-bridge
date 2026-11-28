package it.federicoraponi.watchdeck.ui.cameras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.camera.CameraFeature
import it.federicoraponi.watchdeck.ui.QuickActions
import it.federicoraponi.watchdeck.ui.camera.FeaturePanel
import it.federicoraponi.watchdeck.ui.designsystem.ActionRow
import it.federicoraponi.watchdeck.ui.designsystem.SheetTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.rememberFullSheetState

/** What a held camera offers: opening it, its switches for administrators, and hiding it from the group. */
@Composable
internal fun QuickActionsSheet(
    quick: QuickActions,
    onDismiss: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenRecordings: () -> Unit,
    onFeature: (CameraFeature, Boolean) -> Unit,
    onLiveOnly: (Boolean) -> Unit,
    onHide: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SheetTitle(quick.camera.displayName, modifier = Modifier.padding(start = Spacing.sm))
            ActionRow {
                OpenButton(R.string.video_live, onOpenLive) { Icon(Symbols.PlayArrow, contentDescription = null) }
                if (quick.camera.canRecord) {
                    OpenButton(R.string.quick_recordings, onOpenRecordings) {
                        Icon(Symbols.VideoLibrary, contentDescription = null)
                    }
                }
            }
            if (quick.controls.isAllowed && quick.camera.enabled) {
                FeaturePanel(controls = quick.controls, onChange = onFeature, onLiveOnly = onLiveOnly)
            }
            if (quick.canHide) {
                TextButton(onClick = onHide, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.quick_hide))
                }
            }
        }
    }
}

@Composable
private fun OpenButton(
    label: Int,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        icon()
        Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
    }
}
