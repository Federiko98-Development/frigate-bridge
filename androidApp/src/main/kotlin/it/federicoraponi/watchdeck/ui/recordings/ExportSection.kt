package it.federicoraponi.watchdeck.ui.recordings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.server.ExportedFile
import it.federicoraponi.watchdeck.ui.ExportState
import it.federicoraponi.watchdeck.ui.designsystem.ActionRow
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.StatusText
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.failureText

/** The button that starts an export. It sits in a row with the other actions, and only while there is no export. */
@Composable
fun ExportButton(onChoose: () -> Unit) {
    FilledTonalButton(onClick = onChoose, shapes = ButtonDefaults.shapes()) {
        Icon(Symbols.Share, contentDescription = null)
        Text(stringResource(R.string.export_clip), modifier = Modifier.padding(start = Spacing.sm))
    }
}

/** The progress of an export and its outcome, across the page under the row of actions. Nothing before it starts. */
@Composable
fun ExportStatus(
    export: ExportState,
    onDone: () -> Unit,
) {
    when (export) {
        ExportState.None -> Unit
        ExportState.Working -> {
            StatusText(stringResource(R.string.export_working))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ExportState.Ready -> ReadyExport(export.file, onDone = onDone)
        is ExportState.Failed ->
            NoticeCard(
                message = failureText(export.failure, R.string.export_failed),
                actionLabel = stringResource(R.string.close),
                onAction = onDone,
                announce = true,
            )
    }
}

@Composable
private fun ReadyExport(
    file: ExportedFile,
    onDone: () -> Unit,
) {
    val sharing = rememberExportSharing(file)
    if (sharing.isBusy) {
        DownloadProgress(onCancel = sharing::cancel)
    } else {
        ActionRow {
            PrimaryButton(text = stringResource(R.string.export_share), onClick = { sharing.share(file) })
            TextButton(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
        }
    }
    SharingFailure(sharing.failure)
}
