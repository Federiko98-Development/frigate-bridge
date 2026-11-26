package it.federicoraponi.frigatebridge.ui.recordings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.ui.ExportState
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.StatusText
import it.federicoraponi.frigatebridge.ui.designsystem.failureText

/** The button that starts an export, then its progress and its outcome. */
@Composable
fun ExportSection(
    export: ExportState,
    onChoose: () -> Unit,
    onDone: () -> Unit,
) {
    when (export) {
        ExportState.None ->
            FilledTonalButton(onClick = onChoose, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(R.string.export_clip), modifier = Modifier.padding(start = Spacing.sm))
            }
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
        PrimaryButton(text = stringResource(R.string.export_share), onClick = { sharing.share(file) })
        TextButton(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
    }
    SharingFailure(sharing.failure)
}
