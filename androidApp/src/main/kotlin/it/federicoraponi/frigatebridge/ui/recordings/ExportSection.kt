package it.federicoraponi.frigatebridge.ui.recordings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.ui.ExportState
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
            Text(stringResource(R.string.export_working), style = MaterialTheme.typography.bodyMedium)
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ExportState.Ready -> ReadyExport(export.file, onDone = onDone)
        is ExportState.Failed -> {
            val problem = export.failure.problem
            val title = if (problem != null) problemText(problem) else stringResource(R.string.export_failed)
            NoticeCard(
                message = listOfNotNull(title, export.failure.detail).joinToString("\n"),
                actionLabel = stringResource(R.string.close),
                onAction = onDone,
            )
        }
    }
}

@Composable
private fun ReadyExport(
    file: ExportedFile,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(file) { mutableStateOf(false) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    if (downloading) {
        Text(stringResource(R.string.export_downloading), style = MaterialTheme.typography.bodyMedium)
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        PrimaryButton(
            text = stringResource(R.string.export_share),
            onClick = {
                downloading = true
                failure = null
                scope.launch {
                    try {
                        context.downloadAndShare(file)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        failure = error.message ?: error.javaClass.simpleName
                    }
                    downloading = false
                }
            },
        )
        TextButton(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
    }
    failure?.let { NoticeCard(stringResource(R.string.export_failed) + "\n" + it) }
}
