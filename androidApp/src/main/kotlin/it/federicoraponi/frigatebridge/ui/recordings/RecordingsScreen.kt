package it.federicoraponi.frigatebridge.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.CamerasContent
import it.federicoraponi.frigatebridge.ui.CamerasState
import it.federicoraponi.frigatebridge.ui.CamerasViewModel
import it.federicoraponi.frigatebridge.ui.cameras.CamerasActions
import it.federicoraponi.frigatebridge.ui.cameras.ServerSwitcher
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.MessageState
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.RefreshableContent
import it.federicoraponi.frigatebridge.ui.designsystem.ShapedIcon
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import org.koin.androidx.compose.koinViewModel

/** The recordings of the active server, camera by camera. */
@Composable
fun RecordingsRoute(
    onShowServers: () -> Unit,
    onOpenRecording: (CameraRef) -> Unit,
    bottomBar: @Composable () -> Unit,
    viewModel: CamerasViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.content) {
        if (state.content == CamerasContent.NoServer) onShowServers()
    }
    RecordingsScreen(
        state = state,
        actions =
            CamerasActions(
                showServers = onShowServers,
                switchTo = viewModel::switchTo,
                retry = viewModel::retry,
                openCamera = onOpenRecording,
            ),
        bottomBar = bottomBar,
    )
}

@Composable
fun RecordingsScreen(
    state: CamerasState,
    actions: CamerasActions,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        bottomBar = bottomBar,
        topBar = { TopAppBar(title = { ServerSwitcher(state, actions) }) },
    ) { innerPadding ->
        RefreshableContent(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.retry,
            modifier = Modifier.padding(innerPadding),
        ) {
            when (val cameras = state.content) {
                CamerasContent.Loading, CamerasContent.NoServer -> LoadingContent()
                is CamerasContent.Failed ->
                    MessageState(
                        icon = Icons.Filled.Warning,
                        title = problemText(cameras.problem),
                        isError = true,
                    ) {
                        PrimaryButton(text = stringResource(R.string.retry), onClick = actions.retry)
                    }
                is CamerasContent.Ready -> CameraRecordings(cameras.cameras, actions.openCamera)
            }
        }
    }
}

@Composable
private fun CameraRecordings(
    cameras: List<Camera>,
    onOpen: (CameraRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cameras.isEmpty()) {
        MessageState(icon = Icons.Filled.Warning, title = stringResource(R.string.cameras_empty), modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(cameras, key = { _, camera -> camera.ref.cameraName }) { index, camera ->
            SegmentedListItem(
                onClick = { onOpen(camera.ref) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = cameras.size),
                colors =
                    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = {
                    ShapedIcon(
                        Icons.Filled.PlayArrow,
                        size = 40.dp,
                        shape = MaterialShapes.Cookie9Sided,
                    )
                },
                supportingContent = {
                    val text =
                        if (camera.isRecording) R.string.recordings_last_minute else R.string.recordings_off
                    Text(stringResource(text), style = MaterialTheme.typography.bodySmall)
                },
            ) { Text(camera.displayName, style = MaterialTheme.typography.titleSmallEmphasized) }
        }
    }
}
