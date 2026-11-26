package it.federicoraponi.frigatebridge.ui.exports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.RecordingClip
import it.federicoraponi.frigatebridge.ui.ExportArgs
import it.federicoraponi.frigatebridge.ui.ExportViewModel
import it.federicoraponi.frigatebridge.ui.ExportViewState
import it.federicoraponi.frigatebridge.ui.SeekRequest
import it.federicoraponi.frigatebridge.ui.camera.BufferingOverlay
import it.federicoraponi.frigatebridge.ui.camera.Failure
import it.federicoraponi.frigatebridge.ui.camera.FullscreenButton
import it.federicoraponi.frigatebridge.ui.camera.FullscreenPlayer
import it.federicoraponi.frigatebridge.ui.camera.LandscapeFullscreen
import it.federicoraponi.frigatebridge.ui.camera.RecordingPicture
import it.federicoraponi.frigatebridge.ui.camera.RecordingTransport
import it.federicoraponi.frigatebridge.ui.camera.VideoFrame
import it.federicoraponi.frigatebridge.ui.camera.ZoomableVideo
import it.federicoraponi.frigatebridge.ui.camera.rememberRecordingPlayer
import it.federicoraponi.frigatebridge.ui.camera.seekBy
import it.federicoraponi.frigatebridge.ui.capture.LocalFrameCapture
import it.federicoraponi.frigatebridge.ui.capture.PhotoButton
import it.federicoraponi.frigatebridge.ui.capture.rememberCaptures
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.KeepScreenOn
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.SheetTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.failureText
import it.federicoraponi.frigatebridge.ui.designsystem.rememberFullSheetState
import it.federicoraponi.frigatebridge.ui.recordings.DownloadProgress
import it.federicoraponi.frigatebridge.ui.recordings.SharingFailure
import it.federicoraponi.frigatebridge.ui.recordings.rememberExportSharing
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** One video kept on the server. */
@Composable
fun ExportRoute(
    serverId: String,
    exportId: String,
    onClose: () -> Unit,
    viewModel: ExportViewModel = koinViewModel { parametersOf(ExportArgs(serverId, exportId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    KeepScreenOn()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val player =
        state.file?.let { file ->
            val clip = RecordingClip(file.url, file.headers, file.certificateSha256, 0, 0)
            rememberRecordingPlayer(
                clip = clip,
                seek = SeekRequest(0, 0),
                speed = 1f,
                onPosition = {},
                onEnded = {},
                onError = viewModel::playerFailed,
            )
        }
    val captures = rememberCaptures()
    val name = state.item?.title.orEmpty()
    val takePhoto =
        player?.let { current ->
            { captures.takePhoto(name, (state.item?.date ?: 0) + current.currentPosition / 1_000) }
        }
    // The video moves between the page and full screen without being rebuilt, so it keeps playing.
    val video =
        remember {
            movableContentOf { shown: ExportViewState, current: Player?, retry: () -> Unit ->
                CompositionLocalProvider(LocalFrameCapture provides captures.frames) {
                    ExportVideo(shown, current, retry)
                }
            }
        }
    val picture: @Composable () -> Unit = { video(state, player, viewModel::retry) }
    if (fullscreen) {
        BackHandler { fullscreen = false }
        LandscapeFullscreen()
        val title = name.replace('_', ' ')
        FullscreenPlayer(title, player, captures.messages, takePhoto, onExit = { fullscreen = false }) { picture() }
        return
    }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val sharing = rememberExportSharing()
    Scaffold(
        snackbarHost = { SnackbarHost(captures.messages) },
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.export_page_title)) },
                navigationIcon = {
                    BackButton(onClose)
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VideoFrame { picture() }
            RecordingTransport(player, onSkip = { player?.seekBy(it) })
            takePhoto?.let { PhotoButton(it) }
            FullscreenButton { fullscreen = true }
            if (name.isNotEmpty()) {
                Text(
                    name.replace('_', ' '),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                )
            }
            state.file?.let { file ->
                if (sharing.isBusy) {
                    DownloadProgress(onCancel = sharing::cancel)
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.export_save),
                        onClick = {
                            val item = state.item ?: return@PrimaryButton
                            sharing.whileBusy { captures.saveClip(file, item.camera.cameraName, item.date) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(onClick = { sharing.share(file) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text(stringResource(R.string.export_share), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            }
            SharingFailure(sharing)
            val item = state.item
            if (item != null && !item.inProgress) {
                TextButton(
                    onClick = { confirming = true },
                    enabled = !state.isDeleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(stringResource(R.string.export_delete), modifier = Modifier.padding(start = Spacing.sm))
                }
            }
            if (state.isDeleting) ContainedLoadingIndicator()
            // A failure to play is told over the video; this one is about deleting.
            if (state.file != null) {
                state.failure?.let { NoticeCard(message = failureText(it, R.string.control_failed), announce = true) }
            }
        }
    }
    if (confirming) {
        DeleteSheet(
            isSaved = state.item?.savedReviewId != null,
            onDismiss = { confirming = false },
            onDelete = {
                confirming = false
                viewModel.delete()
            },
        )
    }
}

@Composable
private fun ExportVideo(
    state: ExportViewState,
    player: Player?,
    onRetry: () -> Unit,
) {
    ZoomableVideo(resetKey = null, enabled = player != null) { picture ->
        val failure = state.failure
        when {
            player != null -> {
                RecordingPicture(player, picture)
                BufferingOverlay(player)
            }
            failure != null -> Failure(failure, R.string.video_failed, onRetry)
            else -> ContainedLoadingIndicator()
        }
    }
}

/** Asks before a file is removed from the server, which cannot be undone. */
@Composable
private fun DeleteSheet(
    isSaved: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFullSheetState(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            SheetTitle(stringResource(R.string.export_delete_title))
            Text(
                stringResource(if (isSaved) R.string.export_delete_saved else R.string.export_delete_message),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onDelete,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.export_delete)) }
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
