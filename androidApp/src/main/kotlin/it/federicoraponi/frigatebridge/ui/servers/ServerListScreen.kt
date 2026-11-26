package it.federicoraponi.frigatebridge.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.ConnectionProblem
import it.federicoraponi.frigatebridge.ui.ServerListItem
import it.federicoraponi.frigatebridge.ui.ServerListState
import it.federicoraponi.frigatebridge.ui.ServerListViewModel
import it.federicoraponi.frigatebridge.ui.ServerStatus
import it.federicoraponi.frigatebridge.ui.designsystem.ExpressiveMenu
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.LocalNetworkNotice
import it.federicoraponi.frigatebridge.ui.designsystem.MenuEntry
import it.federicoraponi.frigatebridge.ui.designsystem.MessageState
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.ServerBadge
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import it.federicoraponi.frigatebridge.ui.rememberAllowLocalNetwork
import it.federicoraponi.frigatebridge.ui.rememberLocalNetworkAccess
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

data class ServerListActions(
    val open: (String) -> Unit = {},
    val add: () -> Unit = {},
    val edit: (String) -> Unit = {},
    val toggleDefault: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val refresh: () -> Unit = {},
    val allowLocalNetwork: () -> Unit = {},
)

@Composable
fun ServerListRoute(
    onOpenCameras: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    viewModel: ServerListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkAccess()
    val allowLocalNetwork = rememberAllowLocalNetwork(localNetwork, onAllowed = viewModel::refresh)
    ServerListScreen(
        state = state,
        localNetworkBlocked = !localNetwork.isGranted && state.servers.any { it.isUnreachable },
        actions =
            ServerListActions(
                open = {
                    viewModel.open(it)
                    onOpenCameras()
                },
                add = onAddServer,
                edit = onEditServer,
                toggleDefault = viewModel::toggleDefault,
                remove = viewModel::remove,
                refresh = viewModel::refresh,
                allowLocalNetwork = allowLocalNetwork,
            ),
    )
}

private val ServerListItem.isUnreachable: Boolean
    get() = status == ServerStatus.Unavailable(ConnectionProblem.Unreachable)

@Composable
fun ServerListScreen(
    state: ServerListState,
    actions: ServerListActions,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.servers_title)) },
                actions = {
                    IconButton(onClick = actions.refresh, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // The empty state has its own button.
            if (state.servers.isNotEmpty()) AddServerButton(actions.add)
        },
    ) { innerPadding ->
        val content = Modifier.padding(innerPadding)
        when {
            state.isLoading -> LoadingContent(content)
            state.servers.isEmpty() ->
                MessageState(
                    icon = Icons.Filled.Home,
                    title = stringResource(R.string.servers_empty_title),
                    message = stringResource(R.string.servers_empty_message),
                    modifier = content,
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.server_add),
                        onClick = actions.add,
                        leading = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                }
            else ->
                ServerList(
                    servers = state.servers,
                    actions = actions,
                    onRemove = { removing = it },
                    localNetworkBlocked = localNetworkBlocked,
                    modifier = content,
                )
        }
    }
    state.servers.firstOrNull { it.id == removing }?.let { server ->
        RemoveDialog(
            name = server.name,
            onConfirm = {
                removing = null
                actions.remove(server.id)
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun AddServerButton(onClick: () -> Unit) {
    val label = stringResource(R.string.server_add)
    SmallExtendedFloatingActionButton(
        onClick = onClick,
        // The button hides its text from accessibility services, so the icon carries the label.
        icon = { Icon(Icons.Filled.Add, contentDescription = label) },
        text = { Text(label) },
    )
}

/** The default server is the hero of the screen; the others form a segmented list. */
@Composable
private fun ServerList(
    servers: List<ServerListItem>,
    actions: ServerListActions,
    onRemove: (String) -> Unit,
    localNetworkBlocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val default = servers.firstOrNull { it.isDefault }
    val others = servers.filterNot { it.isDefault }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Room for the floating button below the last item.
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (localNetworkBlocked) {
            item(key = "local-network") {
                LocalNetworkNotice(actions.allowLocalNetwork, Modifier.padding(bottom = Spacing.lg))
            }
        }
        if (default != null) {
            item(key = default.id) {
                DefaultServerCard(default, actions, onRemove, Modifier.animateItem())
            }
        }
        if (others.isNotEmpty()) {
            item(key = "title") {
                val title = if (default == null) R.string.servers_all else R.string.servers_other
                ListGroup(
                    title = stringResource(title),
                    modifier = Modifier.padding(top = if (default == null) 0.dp else Spacing.lg),
                ) {}
            }
            itemsIndexed(others, key = { _, server -> server.id }) { index, server ->
                ServerRow(server, index, others.size, actions, onRemove, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun DefaultServerCard(
    server: ServerListItem,
    actions: ServerListActions,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { actions.open(server.id) },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            ServerBadge(server.name, server.colorIndex, size = 48.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.server_default), style = MaterialTheme.typography.labelMedium)
                }
                Text(server.name, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    server.address,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(server.status)
            }
            ServerMenu(server, actions, onRemove)
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerListItem,
    index: Int,
    count: Int,
    actions: ServerListActions,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = { actions.open(server.id) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
        leadingContent = { ServerBadge(server.name, server.colorIndex) },
        trailingContent = { ServerMenu(server, actions, onRemove) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    server.address,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(server.status)
            }
        },
    ) { Text(server.name, style = MaterialTheme.typography.titleSmallEmphasized) }
}

@Composable
private fun StatusPill(status: ServerStatus) {
    val colors = MaterialTheme.colorScheme
    val (text, container, content) =
        when (status) {
            ServerStatus.Checking ->
                Triple(stringResource(R.string.status_checking), colors.surfaceContainerHighest, colors.onSurface)
            ServerStatus.Online ->
                Triple(stringResource(R.string.status_online), colors.tertiaryContainer, colors.onTertiaryContainer)
            is ServerStatus.Unavailable ->
                Triple(problemText(status.problem), colors.errorContainer, colors.onErrorContainer)
        }
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun ServerMenu(
    server: ServerListItem,
    actions: ServerListActions,
    onRemove: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val defaultLabel = if (server.isDefault) R.string.server_unset_default else R.string.server_set_default
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.server_options, server.name),
            )
        }
        ExpressiveMenu(
            expanded = open,
            onDismiss = { open = false },
            entries =
                listOf(
                    MenuEntry(
                        label = stringResource(R.string.edit),
                        onClick = { actions.edit(server.id) },
                        leading = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    ),
                    MenuEntry(
                        label = stringResource(defaultLabel),
                        onClick = { actions.toggleDefault(server.id) },
                        leading = { Icon(Icons.Filled.Star, contentDescription = null) },
                    ),
                    MenuEntry(
                        label = stringResource(R.string.remove),
                        onClick = { onRemove(server.id) },
                        leading = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    ),
                ),
        )
    }
}

@Composable
private fun RemoveDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.server_remove_title, name)) },
        text = { Text(stringResource(R.string.server_remove_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private val previewServers =
    listOf(
        ServerListItem("1", "Home", "https://home.example.com", 0, true, ServerStatus.Online),
        ServerListItem(
            "2",
            "Office",
            "https://office.example.com",
            1,
            false,
            ServerStatus.Unavailable(ConnectionProblem.Unreachable),
        ),
    )

@Preview
@Composable
private fun ServerListScreenPreview() {
    FrigateBridgeTheme {
        ServerListScreen(ServerListState(isLoading = false, servers = previewServers), ServerListActions())
    }
}

@Preview
@Composable
private fun ServerListScreenEmptyPreview() {
    FrigateBridgeTheme {
        ServerListScreen(ServerListState(isLoading = false), ServerListActions())
    }
}
