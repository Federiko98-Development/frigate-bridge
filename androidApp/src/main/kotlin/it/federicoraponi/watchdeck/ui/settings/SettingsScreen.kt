package it.federicoraponi.watchdeck.ui.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.security.canAuthenticateUser
import it.federicoraponi.watchdeck.settings.AppSettings
import it.federicoraponi.watchdeck.settings.GroupButtons
import it.federicoraponi.watchdeck.settings.HomeLayout
import it.federicoraponi.watchdeck.settings.PreviewRefresh
import it.federicoraponi.watchdeck.settings.ThemeMode
import it.federicoraponi.watchdeck.ui.AccountEntry
import it.federicoraponi.watchdeck.ui.SettingsViewModel
import it.federicoraponi.watchdeck.ui.designsystem.ActionRow
import it.federicoraponi.watchdeck.ui.designsystem.BackButton
import it.federicoraponi.watchdeck.ui.designsystem.ConnectedChoice
import it.federicoraponi.watchdeck.ui.designsystem.ControlGroup
import it.federicoraponi.watchdeck.ui.designsystem.ListGroup
import it.federicoraponi.watchdeck.ui.designsystem.ScreenTitle
import it.federicoraponi.watchdeck.ui.designsystem.ShapedIcon
import it.federicoraponi.watchdeck.ui.designsystem.SheetTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.rememberFullSheetState
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
import org.koin.androidx.compose.koinViewModel

private val TILE_INNER_CORNER = 4.dp
private const val SOURCE_URL = "https://github.com/Federiko98-Development/watchdeck"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"

data class SettingsActions(
    val setThemeMode: (ThemeMode) -> Unit = {},
    val setDynamicColor: (Boolean) -> Unit = {},
    val setHomeLayout: (HomeLayout) -> Unit = {},
    val setGroupButtons: (GroupButtons) -> Unit = {},
    val setPreviewRefresh: (PreviewRefresh) -> Unit = {},
    val setSavePreviewData: (Boolean) -> Unit = {},
    val manageServers: () -> Unit = {},
    val setAppLock: (Boolean) -> Unit = {},
    val openStatus: () -> Unit = {},
    val openExports: () -> Unit = {},
    val openSaved: () -> Unit = {},
    val openAccess: () -> Unit = {},
    val changePassword: () -> Unit = {},
    val openCameraGroups: () -> Unit = {},
)

/** The app's settings. [onBack] is set when the screen was opened on top of another one, not as a tab. */
@Composable
fun SettingsRoute(
    onManageServers: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onOpenStatus: () -> Unit = {},
    onOpenExports: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenAccess: () -> Unit = {},
    onChangePassword: () -> Unit = {},
    onOpenCameraGroups: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = state.settings ?: AppSettings(),
        actions =
            SettingsActions(
                setThemeMode = viewModel::setThemeMode,
                setDynamicColor = viewModel::setDynamicColor,
                setHomeLayout = viewModel::setHomeLayout,
                setGroupButtons = viewModel::setGroupButtons,
                setPreviewRefresh = viewModel::setPreviewRefresh,
                setSavePreviewData = viewModel::setSavePreviewData,
                manageServers = onManageServers,
                setAppLock = onAppLockChange,
                openStatus = onOpenStatus,
                openExports = onOpenExports,
                openSaved = onOpenSaved,
                openAccess = onOpenAccess,
                changePassword = onChangePassword,
                openCameraGroups = onOpenCameraGroups,
            ),
        managedServer = state.managedServer,
        account = state.account,
        // Looked up again every time the screen is drawn: the phone's screen lock may have just been set up.
        canLock = LocalContext.current.canAuthenticateUser(),
        onBack = onBack,
        bottomBar = bottomBar,
    )
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    canLock: Boolean = true,
    /** The active server's name, when its administrator's pages are offered. */
    managedServer: String? = null,
    /** The signed-in account of the active server, when its password can be changed here. */
    account: AccountEntry? = null,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    // Saved, so that it stays open when changing the language or the theme draws the screen anew.
    var open by rememberSaveable { mutableStateOf<SettingsPanel?>(null) }
    Scaffold(
        modifier = modifier,
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        BackButton(onBack)
                    }
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
        ) {
            // What concerns the active server comes first, as tiles: everything is in sight without scrolling.
            ServerTiles(account?.serverName ?: managedServer, account, isManaged = managedServer != null, actions)
            Preferences(settings, canLock, actions.setAppLock, onOpen = { open = it })
            About()
        }
    }
    when (open) {
        SettingsPanel.Appearance ->
            SettingsSheet(R.string.settings_appearance, { open = null }) {
                Appearance(settings, actions)
            }
        SettingsPanel.Cameras ->
            SettingsSheet(R.string.settings_cameras, { open = null }) {
                Cameras(settings, actions)
            }
        null -> Unit
    }
}

/** The groups of preferences that open in a sheet. */
private enum class SettingsPanel {
    Appearance,
    Cameras,
}

@Composable
private fun SettingsSheet(
    title: Int,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            SheetTitle(stringResource(title), modifier = Modifier.padding(start = Spacing.sm, bottom = Spacing.md))
            content()
        }
    }
}

/** Theme, dynamic colour and, where Android keeps one per app, the language. */
@Composable
private fun Appearance(
    settings: AppSettings,
    actions: SettingsActions,
) {
    // Dynamic colour needs Android 12, a language for the app Android 13.
    val hasDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasLanguage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val count = 1 + (if (hasDynamicColor) 1 else 0) + (if (hasLanguage) 1 else 0)
    ChoiceItem(
        title = stringResource(R.string.settings_theme),
        icon = Symbols.DarkMode,
        options =
            listOf(
                ThemeMode.System to stringResource(R.string.settings_system),
                ThemeMode.Light to stringResource(R.string.settings_theme_light),
                ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
            ),
        selected = settings.themeMode,
        onSelect = actions.setThemeMode,
        index = 0,
        count = count,
    )
    if (hasDynamicColor) {
        SwitchItem(
            title = stringResource(R.string.settings_dynamic_color),
            icon = Symbols.Palette,
            description = stringResource(R.string.settings_dynamic_color_hint),
            checked = settings.dynamicColor,
            onCheckedChange = actions.setDynamicColor,
            index = 1,
            count = count,
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Language(index = count - 1, count = count)
}

// The language is kept by Android, which also offers it in the system settings.
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Language(
    index: Int,
    count: Int,
) {
    val manager = LocalContext.current.getSystemService(LocaleManager::class.java)
    val current = manager.applicationLocales.toLanguageTags().substringBefore('-')
    ChoiceItem(
        title = stringResource(R.string.settings_app_language),
        icon = Symbols.Language,
        options =
            listOf(
                "" to stringResource(R.string.settings_system),
                "en" to "English",
                // Each language is written in itself.
                "it" to "Italiano",
            ),
        selected = current,
        onSelect = { manager.applicationLocales = LocaleList.forLanguageTags(it) },
        index = index,
        count = count,
    )
}

@Composable
private fun Cameras(
    settings: AppSettings,
    actions: SettingsActions,
) {
    ChoiceItem(
        title = stringResource(R.string.settings_layout),
        icon = Symbols.Dashboard,
        options =
            listOf(
                HomeLayout.Grid to stringResource(R.string.settings_layout_grid),
                HomeLayout.List to stringResource(R.string.settings_layout_list),
            ),
        selected = settings.homeLayout,
        onSelect = actions.setHomeLayout,
        index = 0,
        count = 4,
    )
    ChoiceItem(
        title = stringResource(R.string.settings_group_buttons),
        icon = Symbols.Label,
        options =
            listOf(
                GroupButtons.Names to stringResource(R.string.settings_group_buttons_names),
                GroupButtons.Icons to stringResource(R.string.settings_group_buttons_icons),
            ),
        selected = settings.groupButtons,
        onSelect = actions.setGroupButtons,
        index = 1,
        count = 4,
    )
    ChoiceItem(
        title = stringResource(R.string.settings_previews),
        icon = Symbols.Autorenew,
        options =
            listOf(
                PreviewRefresh.Fast to stringResource(R.string.settings_previews_fast),
                PreviewRefresh.Slow to stringResource(R.string.settings_previews_slow),
                PreviewRefresh.OnOpen to stringResource(R.string.settings_previews_once),
            ),
        selected = settings.previewRefresh,
        onSelect = actions.setPreviewRefresh,
        index = 2,
        count = 4,
    )
    SwitchItem(
        title = stringResource(R.string.settings_save_data),
        icon = Symbols.DataSaverOn,
        description = stringResource(R.string.settings_save_data_hint),
        checked = settings.savePreviewData,
        onCheckedChange = actions.setSavePreviewData,
        index = 3,
        count = 4,
    )
}

/** The active server's pages, and the list of servers, two to a row. */
@Composable
private fun ServerTiles(
    name: String?,
    account: AccountEntry?,
    isManaged: Boolean,
    actions: SettingsActions,
) {
    val tiles =
        buildList {
            if (isManaged) {
                add(Tile(R.string.status_title, Symbols.MonitorHeart, actions.openStatus))
                add(Tile(R.string.saved_title, Symbols.Bookmark, actions.openSaved))
                add(Tile(R.string.exports_title, Symbols.VideoLibrary, actions.openExports))
                add(Tile(R.string.access_title, Symbols.Group, actions.openAccess))
                add(Tile(R.string.group_title, Symbols.Category, actions.openCameraGroups))
            }
            if (account != null) {
                add(Tile(R.string.password_change_title, Symbols.Key, actions.changePassword, account.username))
            }
            add(Tile(R.string.settings_manage_servers, Symbols.Dns, actions.manageServers))
        }
    ListGroup(title = name ?: stringResource(R.string.servers_title)) {
        // As in a segmented list, only the corners of the whole group are fully round.
        val rows = tiles.chunked(2)
        rows.forEachIndexed { row, pair ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                pair.forEachIndexed { column, tile ->
                    val outer = MaterialTheme.shapes.large.topStart
                    val small = CornerSize(TILE_INNER_CORNER)
                    val isLast = row == rows.lastIndex
                    val isFirstColumn = column == 0
                    val isLastColumn = column == pair.lastIndex
                    TileButton(
                        tile,
                        shape =
                            RoundedCornerShape(
                                topStart = if (row == 0 && isFirstColumn) outer else small,
                                topEnd = if (row == 0 && isLastColumn) outer else small,
                                bottomEnd = if (isLast && isLastColumn) outer else small,
                                bottomStart = if (isLast && isFirstColumn) outer else small,
                            ),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

private class Tile(
    val title: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val detail: String? = null,
)

@Composable
private fun TileButton(
    tile: Tile,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = tile.onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShapedIcon(tile.icon, size = 36.dp)
            Column {
                Text(stringResource(tile.title), style = MaterialTheme.typography.titleSmall)
                tile.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The app's own preferences: one row for each group, saying what is chosen. */
@Composable
private fun Preferences(
    settings: AppSettings,
    canLock: Boolean,
    onLockChange: (Boolean) -> Unit,
    onOpen: (SettingsPanel) -> Unit,
) {
    val theme =
        stringResource(
            when (settings.themeMode) {
                ThemeMode.System -> R.string.settings_system
                ThemeMode.Light -> R.string.settings_theme_light
                ThemeMode.Dark -> R.string.settings_theme_dark
            },
        )
    val layout =
        stringResource(
            if (settings.homeLayout == HomeLayout.Grid) {
                R.string.settings_layout_grid
            } else {
                R.string.settings_layout_list
            },
        )
    ListGroup(title = stringResource(R.string.settings_app)) {
        LinkItem(
            title = stringResource(R.string.settings_appearance),
            icon = Symbols.Palette,
            description = stringResource(R.string.settings_appearance_summary, theme),
            onClick = { onOpen(SettingsPanel.Appearance) },
            index = 0,
            count = 3,
        )
        LinkItem(
            title = stringResource(R.string.settings_cameras),
            icon = Symbols.Dashboard,
            description = stringResource(R.string.settings_cameras_summary, layout),
            onClick = { onOpen(SettingsPanel.Cameras) },
            index = 1,
            count = 3,
        )
        SwitchItem(
            title = stringResource(R.string.settings_app_lock),
            icon = Symbols.Lock,
            description =
                stringResource(
                    if (canLock) R.string.settings_app_lock_hint else R.string.settings_app_lock_unavailable,
                ),
            // Without a screen lock the app cannot be locked, whatever was saved.
            checked = settings.appLock && canLock,
            onCheckedChange = { if (canLock) onLockChange(it) },
            index = 2,
            count = 3,
        )
    }
}

/** The source and the privacy policy, with the version, at the foot of the page. */
@Composable
private fun About() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version =
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
    ControlGroup {
        ActionRow {
            TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.settings_source))
            }
            TextButton(onClick = { uriHandler.openUri(PRIVACY_URL) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.settings_privacy))
            }
        }
        Text(
            stringResource(R.string.settings_about_hint, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun <T> ChoiceItem(
    title: String,
    icon: ImageVector,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    index: Int,
    count: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // The icon stands in the middle of the whole card, beside the title and the choices.
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                ConnectedChoice(
                    labels = options.map { it.second },
                    selected = options.indexOfFirst { it.first == selected },
                    onSelect = { onSelect(options[it].first) },
                )
            }
        }
    }
}

@Composable
private fun SwitchItem(
    title: String,
    icon: ImageVector,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        // The switch shows the state, so a row that is on keeps the colours of the others.
        colors =
            ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        leadingContent = { SettingIcon(icon) },
        // Also beside a description of two lines, which would otherwise keep the icon at the top.
        verticalAlignment = Alignment.CenterVertically,
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        // The whole row toggles; the switch only shows the state.
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun LinkItem(
    title: String,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    index: Int,
    count: Int,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { SettingIcon(icon) },
        // Also beside a description of two lines, which would otherwise keep the icon at the top.
        verticalAlignment = Alignment.CenterVertically,
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
    ) { Text(title, style = MaterialTheme.typography.titleSmall) }
}

/** The accent beside each setting, which makes it quicker to find. */
@Composable
private fun SettingIcon(icon: ImageVector) = ShapedIcon(icon, size = 40.dp)

@Preview
@Composable
private fun SettingsScreenPreview() {
    WatchdeckTheme {
        SettingsScreen(AppSettings(), SettingsActions())
    }
}
