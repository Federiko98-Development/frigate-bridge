package it.federicoraponi.frigatebridge.ui.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.security.canAuthenticateUser
import it.federicoraponi.frigatebridge.settings.AppSettings
import it.federicoraponi.frigatebridge.settings.GroupButtons
import it.federicoraponi.frigatebridge.settings.HomeLayout
import it.federicoraponi.frigatebridge.settings.PreviewRefresh
import it.federicoraponi.frigatebridge.settings.ThemeMode
import it.federicoraponi.frigatebridge.ui.AccountEntry
import it.federicoraponi.frigatebridge.ui.SettingsViewModel
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.ShapedIcon
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

private const val ADMIN_PAGES = 5
private const val SOURCE_URL = "https://github.com/Federiko98-Development/frigate-bridge"

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
            Appearance(settings, actions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Language()
            Cameras(settings, actions)
            Security(settings.appLock, canLock, actions.setAppLock)
            // What concerns the active server is in one group, whoever is signed in.
            (account?.serverName ?: managedServer)?.let {
                ThisServer(it, account, isManaged = managedServer != null, actions)
            }
            Servers(actions.manageServers)
            About()
        }
    }
}

@Composable
private fun Appearance(
    settings: AppSettings,
    actions: SettingsActions,
) {
    // Dynamic colour needs Android 12.
    val hasDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val count = if (hasDynamicColor) 2 else 1
    ListGroup(title = stringResource(R.string.settings_appearance)) {
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
    }
}

// The language is kept by Android, which also offers it in the system settings.
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Language() {
    val manager = LocalContext.current.getSystemService(LocaleManager::class.java)
    val current = manager.applicationLocales.toLanguageTags().substringBefore('-')
    ListGroup(title = stringResource(R.string.settings_language)) {
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
            index = 0,
            count = 1,
        )
    }
}

@Composable
private fun Security(
    isLocked: Boolean,
    canLock: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListGroup(title = stringResource(R.string.settings_security)) {
        SwitchItem(
            title = stringResource(R.string.settings_app_lock),
            icon = Symbols.Lock,
            description =
                stringResource(
                    if (canLock) R.string.settings_app_lock_hint else R.string.settings_app_lock_unavailable,
                ),
            // Without a screen lock the app cannot be locked, whatever was saved.
            checked = isLocked && canLock,
            onCheckedChange = { if (canLock) onChange(it) },
            index = 0,
            count = 1,
        )
    }
}

@Composable
private fun Cameras(
    settings: AppSettings,
    actions: SettingsActions,
) {
    ListGroup(title = stringResource(R.string.settings_cameras)) {
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
}

@Composable
private fun Servers(onManage: () -> Unit) {
    ListGroup(title = stringResource(R.string.servers_title)) {
        LinkItem(
            title = stringResource(R.string.settings_manage_servers),
            icon = Symbols.Dns,
            description = stringResource(R.string.settings_manage_servers_hint),
            onClick = onManage,
            index = 0,
            count = 1,
        )
    }
}

@Composable
private fun ThisServer(
    name: String,
    account: AccountEntry?,
    isManaged: Boolean,
    actions: SettingsActions,
) {
    // The password comes first, for those who have one; the administrator's pages follow.
    val first = if (account == null) 0 else 1
    val count = first + if (isManaged) ADMIN_PAGES else 0
    ListGroup(title = name) {
        if (account != null) {
            LinkItem(
                title = stringResource(R.string.password_change_title),
                icon = Symbols.Key,
                description = stringResource(R.string.password_signed_in_as, account.username),
                onClick = actions.changePassword,
                index = 0,
                count = count,
            )
        }
        if (isManaged) {
            LinkItem(
                title = stringResource(R.string.status_title),
                icon = Symbols.MonitorHeart,
                description = stringResource(R.string.status_hint),
                onClick = actions.openStatus,
                index = first,
                count = count,
            )
            LinkItem(
                title = stringResource(R.string.saved_title),
                icon = Symbols.Bookmark,
                description = stringResource(R.string.saved_hint),
                onClick = actions.openSaved,
                index = first + 1,
                count = count,
            )
            LinkItem(
                title = stringResource(R.string.exports_title),
                icon = Symbols.VideoLibrary,
                description = stringResource(R.string.exports_hint),
                onClick = actions.openExports,
                index = first + 2,
                count = count,
            )
            LinkItem(
                title = stringResource(R.string.access_title),
                icon = Symbols.Group,
                description = stringResource(R.string.access_hint),
                onClick = actions.openAccess,
                index = first + 3,
                count = count,
            )
            LinkItem(
                title = stringResource(R.string.group_title),
                icon = Symbols.Category,
                description = stringResource(R.string.group_hint),
                onClick = actions.openCameraGroups,
                index = first + 4,
                count = count,
            )
        }
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version =
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
    ListGroup(title = stringResource(R.string.settings_about)) {
        LinkItem(
            title = stringResource(R.string.settings_source),
            icon = Symbols.Code,
            description = stringResource(R.string.settings_about_hint, version),
            onClick = { uriHandler.openUri(SOURCE_URL) },
            index = 0,
            count = 1,
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
    FrigateBridgeTheme {
        SettingsScreen(AppSettings(), SettingsActions())
    }
}
