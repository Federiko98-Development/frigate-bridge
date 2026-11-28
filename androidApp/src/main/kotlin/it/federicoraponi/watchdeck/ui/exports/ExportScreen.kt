package it.federicoraponi.watchdeck.ui.exports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.recordings.ExportItem
import it.federicoraponi.watchdeck.server.ExportedFile
import it.federicoraponi.watchdeck.server.RecordingClip
import it.federicoraponi.watchdeck.ui.ExportArgs
import it.federicoraponi.watchdeck.ui.ExportViewModel
import it.federicoraponi.watchdeck.ui.ExportViewState
import it.federicoraponi.watchdeck.ui.SeekRequest
import it.federicoraponi.watchdeck.ui.camera.BufferingOverlay
import it.federicoraponi.watchdeck.ui.camera.Failure
import it.federicoraponi.watchdeck.ui.camera.FullscreenButton
import it.federicoraponi.watchdeck.ui.camera.FullscreenPlayer
import it.federicoraponi.watchdeck.ui.camera.LandscapeFullscreen
import it.federicoraponi.watchdeck.ui.camera.RecordingPicture
import it.federicoraponi.watchdeck.ui.camera.RecordingTransport
import it.federicoraponi.watchdeck.ui.camera.VideoFrame
import it.federicoraponi.watchdeck.ui.camera.VideoSeekBar
import it.federicoraponi.watchdeck.ui.camera.ZoomableVideo
import it.federicoraponi.watchdeck.ui.camera.rememberRecordingPlayer
import it.federicoraponi.watchdeck.ui.camera.seekBy
import it.federicoraponi.watchdeck.ui.capture.LocalFrameCapture
import it.federicoraponi.watchdeck.ui.capture.TakePhotoButton
import it.federicoraponi.watchdeck.ui.capture.rememberCaptures
import it.federicoraponi.watchdeck.ui.designsystem.ActionRow
import it.federicoraponi.watchdeck.ui.designsystem.BackButton
import it.federicoraponi.watchdeck.ui.designsystem.ConfirmSheet
import it.federicoraponi.watchdeck.ui.designsystem.ControlGroup
import it.federicoraponi.watchdeck.ui.designsystem.KeepScreenOn
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.ScreenTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.failureText
import it.federicoraponi.watchdeck.ui.recordings.DownloadProgress
import it.federicoraponi.watchdeck.ui.recordings.SharingFailure
import it.federicoraponi.watchdeck.ui.recordings.rememberExportSharing
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
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
            val clip = RecordingClip(file.url, file.authorization, file.certificateSha256, 0, 0)
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
    val sharing = rememberExportSharing()
    ExportScreen(
        state = state,
        player = player,
        isBusy = sharing.isBusy,
        sharingFailure = sharing.failure,
        messages = captures.messages,
        actions =
            ExportActions(
                close = onClose,
                fullscreen = { fullscreen = true },
                takePhoto = takePhoto,
                save = { file, item ->
                    sharing.whileBusy { captures.saveClip(file, item.camera.cameraName, item.date) }
                },
                share = sharing::share,
                cancel = sharing::cancel,
                delete = viewModel::delete,
            ),
        video = { picture() },
    )
}

/** What the page of an export can ask for. */
data class ExportActions(
    val close: () -> Unit = {},
    val fullscreen: () -> Unit = {},
    val takePhoto: (() -> Unit)? = null,
    val save: (ExportedFile, ExportItem) -> Unit = { _, _ -> },
    val share: (ExportedFile) -> Unit = {},
    val cancel: () -> Unit = {},
    val delete: () -> Unit = {},
)

@Composable
fun ExportScreen(
    state: ExportViewState,
    player: Player?,
    isBusy: Boolean,
    sharingFailure: String?,
    messages: SnackbarHostState,
    actions: ExportActions,
    video: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val name = state.item?.title.orEmpty()
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.export_page_title)) },
                navigationIcon = { BackButton(actions.close) },
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
            VideoFrame(video)
            ControlGroup {
                VideoSeekBar(player)
                RecordingTransport(player, onSkip = { player?.seekBy(it) })
            }
            // Full screen is always there, so the row is never empty.
            ActionRow {
                actions.takePhoto?.let { TakePhotoButton(it) }
                FullscreenButton(actions.fullscreen)
            }
            if (name.isNotEmpty()) {
                Text(
                    name.replace('_', ' '),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                )
            }
            val item = state.item
            state.file?.let { file ->
                if (isBusy) {
                    DownloadProgress(onCancel = actions.cancel)
                } else if (item != null) {
                    // Side by side when they fit, one under the other with large fonts.
                    ActionRow {
                        PrimaryButton(
                            text = stringResource(R.string.export_save),
                            onClick = { actions.save(file, item) },
                        )
                        FilledTonalButton(
                            onClick = { actions.share(file) },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Symbols.Share, contentDescription = null)
                            Text(
                                stringResource(R.string.export_share),
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                        }
                    }
                }
            }
            SharingFailure(sharingFailure)
            if (item != null && !item.inProgress) {
                TextButton(
                    onClick = { confirming = true },
                    enabled = !state.isDeleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Symbols.Delete, contentDescription = null)
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
        val saved = state.item?.savedReviewId != null
        ConfirmSheet(
            title = stringResource(R.string.export_delete_title),
            message = stringResource(if (saved) R.string.export_delete_saved else R.string.export_delete_message),
            confirmLabel = stringResource(R.string.export_delete),
            onConfirm = {
                confirming = false
                actions.delete()
            },
            onDismiss = { confirming = false },
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

@Preview
@Composable
private fun ExportScreenPreview() {
    val item = ExportItem("home", "a", CameraRef("home", "front_door"), "Front door clip", 1_800_000_000, "", "", false)
    WatchdeckTheme {
        ExportScreen(
            state = ExportViewState(item = item),
            player = null,
            isBusy = false,
            sharingFailure = null,
            messages = SnackbarHostState(),
            actions = ExportActions(),
            video = {},
        )
    }
}
