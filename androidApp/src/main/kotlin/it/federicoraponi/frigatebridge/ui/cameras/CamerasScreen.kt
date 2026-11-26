package it.federicoraponi.frigatebridge.ui.cameras

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.frigate.MINIMUM_FRIGATE_VERSION
import it.federicoraponi.frigatebridge.ui.CamerasContent
import it.federicoraponi.frigatebridge.ui.CamerasState
import it.federicoraponi.frigatebridge.ui.CamerasViewModel
import it.federicoraponi.frigatebridge.ui.ConnectionProblem
import it.federicoraponi.frigatebridge.ui.ServerSummary
import it.federicoraponi.frigatebridge.ui.designsystem.ExpressiveMenu
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.LocalNetworkNotice
import it.federicoraponi.frigatebridge.ui.designsystem.MenuEntry
import it.federicoraponi.frigatebridge.ui.designsystem.MessageState
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.ServerBadge
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import it.federicoraponi.frigatebridge.ui.rememberAllowLocalNetwork
import it.federicoraponi.frigatebridge.ui.rememberLocalNetworkAccess
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

data class CamerasActions(
    val showServers: () -> Unit = {},
    val switchTo: (String) -> Unit = {},
    val selectGroup: (String?) -> Unit = {},
    val retry: () -> Unit = {},
    val editServer: (String) -> Unit = {},
    val allowLocalNetwork: () -> Unit = {},
)

@Composable
fun CamerasRoute(
    onShowServers: () -> Unit,
    onEditServer: (String) -> Unit,
    viewModel: CamerasViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkAccess()
    val allowLocalNetwork = rememberAllowLocalNetwork(localNetwork, onAllowed = viewModel::retry)
    // The server on screen was deleted, or the system restarted the app and no server is active.
    LaunchedEffect(state.content) {
        if (state.content == CamerasContent.NoServer) onShowServers()
    }
    CamerasScreen(
        state = state,
        localNetworkBlocked =
            !localNetwork.isGranted && state.content == CamerasContent.Failed(ConnectionProblem.Unreachable),
        actions =
            CamerasActions(
                showServers = onShowServers,
                switchTo = viewModel::switchTo,
                selectGroup = viewModel::selectGroup,
                retry = viewModel::retry,
                editServer = onEditServer,
                allowLocalNetwork = allowLocalNetwork,
            ),
    )
}

@Composable
fun CamerasScreen(
    state: CamerasState,
    actions: CamerasActions,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { ServerSwitcher(state, actions) },
                actions = {
                    IconButton(onClick = actions.retry, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.groups.isNotEmpty()) GroupSelector(state, actions.selectGroup)
            if (localNetworkBlocked) {
                LocalNetworkNotice(actions.allowLocalNetwork, Modifier.padding(Spacing.lg))
            }
            when (val content = state.content) {
                CamerasContent.Loading, CamerasContent.NoServer -> LoadingContent()
                is CamerasContent.Failed ->
                    MessageState(
                        icon = Icons.Filled.Warning,
                        title = problemText(content.problem),
                        isError = true,
                    ) {
                        PrimaryButton(text = stringResource(R.string.retry), onClick = actions.retry)
                        TextButton(
                            onClick = { state.server?.let { actions.editServer(it.id) } },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(R.string.server_edit)) }
                    }
                is CamerasContent.Ready -> CameraGrid(content)
            }
        }
    }
}

/** The name of the server on screen; tapping it moves to another one. */
@Composable
private fun ServerSwitcher(
    state: CamerasState,
    actions: CamerasActions,
) {
    val server = state.server ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    val switchLabel = stringResource(R.string.cameras_switch_server)
    Box {
        FilledTonalButton(
            onClick = { open = true },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(start = Spacing.sm, end = Spacing.md),
            modifier = Modifier.semantics { role = Role.DropdownList },
        ) {
            ServerBadge(server.name, server.colorIndex, size = 32.dp)
            Text(
                server.name,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.sm).weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = switchLabel)
        }
        ExpressiveMenu(
            expanded = open,
            onDismiss = { open = false },
            entries =
                state.servers.map { other ->
                    val current = other.id == server.id
                    MenuEntry(
                        label = other.name,
                        onClick = { actions.switchTo(other.id) },
                        leading = { ServerBadge(other.name, other.colorIndex, size = 32.dp) },
                        trailing =
                            if (current) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.server_selected),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                } +
                    MenuEntry(
                        label = stringResource(R.string.cameras_all_servers),
                        onClick = actions.showServers,
                        leading = { Icon(Icons.Filled.Home, contentDescription = null) },
                    ),
        )
    }
}

/** "All cameras" and the server's groups as a connected button group. */
@Composable
private fun GroupSelector(
    state: CamerasState,
    onSelect: (String?) -> Unit,
) {
    val options: List<String?> = listOf(null) + state.groups
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, group ->
            ToggleButton(
                checked = state.selectedGroup == group,
                onCheckedChange = { onSelect(group) },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(group?.replace('_', ' ') ?: stringResource(R.string.cameras_all))
            }
        }
    }
}

@Composable
private fun CameraGrid(content: CamerasContent.Ready) {
    if (content.cameras.isEmpty()) {
        MessageState(icon = Icons.Filled.Home, title = stringResource(R.string.cameras_empty))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        content.outdatedVersion?.let { version ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                NoticeCard(stringResource(R.string.cameras_outdated, version, MINIMUM_FRIGATE_VERSION))
            }
        }
        items(content.cameras, key = { it.ref.cameraName }) { CameraCard(it, Modifier.animateItem()) }
    }
}

@Composable
private fun CameraCard(
    camera: Camera,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.xs)) {
            // The live preview will take this place.
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = MaterialTheme.shapes.medium,
                color = colors.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        color = if (camera.enabled) colors.primaryContainer else colors.surfaceContainerHigh,
                        contentColor =
                            if (camera.enabled) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_videocam),
                            contentDescription = null,
                            modifier = Modifier.padding(Spacing.sm).size(20.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
                Text(
                    camera.displayName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Always one line high, so that the cards of a row line up.
                Text(
                    if (camera.enabled) "" else stringResource(R.string.camera_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private val previewServer = ServerSummary("1", "Home", 0)

@Preview
@Composable
private fun CamerasScreenPreview() {
    FrigateBridgeTheme {
        CamerasScreen(
            state =
                CamerasState(
                    server = previewServer,
                    servers = listOf(previewServer),
                    groups = listOf("Outside"),
                    content =
                        CamerasContent.Ready(
                            cameras =
                                listOf(
                                    Camera(CameraRef("1", "front_door"), "Front door", true, true),
                                    Camera(CameraRef("1", "garage"), "Garage", false, true),
                                ),
                            outdatedVersion = null,
                        ),
                ),
            actions = CamerasActions(),
        )
    }
}

@Preview
@Composable
private fun CamerasScreenErrorPreview() {
    FrigateBridgeTheme {
        CamerasScreen(
            state =
                CamerasState(
                    server = previewServer,
                    content = CamerasContent.Failed(ConnectionProblem.Unreachable),
                ),
            actions = CamerasActions(),
        )
    }
}
