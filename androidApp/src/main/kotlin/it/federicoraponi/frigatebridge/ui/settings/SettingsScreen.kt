package it.federicoraponi.frigatebridge.ui.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.security.canAuthenticateUser
import it.federicoraponi.frigatebridge.settings.AppSettings
import it.federicoraponi.frigatebridge.settings.HomeLayout
import it.federicoraponi.frigatebridge.settings.PreviewRefresh
import it.federicoraponi.frigatebridge.settings.ThemeMode
import it.federicoraponi.frigatebridge.ui.SettingsViewModel
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

private const val SOURCE_URL = "https://github.com/Federiko98-Development/frigate-bridge"

data class SettingsActions(
    val setThemeMode: (ThemeMode) -> Unit = {},
    val setDynamicColor: (Boolean) -> Unit = {},
    val setHomeLayout: (HomeLayout) -> Unit = {},
    val setPreviewRefresh: (PreviewRefresh) -> Unit = {},
    val setSavePreviewData: (Boolean) -> Unit = {},
    val manageServers: () -> Unit = {},
    val setAppLock: (Boolean) -> Unit = {},
)

/** The app's settings. [onBack] is set when the screen was opened on top of another one, not as a tab. */
@Composable
fun SettingsRoute(
    onManageServers: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
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
                setPreviewRefresh = viewModel::setPreviewRefresh,
                setSavePreviewData = viewModel::setSavePreviewData,
                manageServers = onManageServers,
                setAppLock = onAppLockChange,
            ),
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
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
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
            options =
                listOf(
                    HomeLayout.Grid to stringResource(R.string.settings_layout_grid),
                    HomeLayout.List to stringResource(R.string.settings_layout_list),
                ),
            selected = settings.homeLayout,
            onSelect = actions.setHomeLayout,
            index = 0,
            count = 3,
        )
        ChoiceItem(
            title = stringResource(R.string.settings_previews),
            options =
                listOf(
                    PreviewRefresh.Fast to stringResource(R.string.settings_previews_fast),
                    PreviewRefresh.Slow to stringResource(R.string.settings_previews_slow),
                    PreviewRefresh.OnOpen to stringResource(R.string.settings_previews_once),
                ),
            selected = settings.previewRefresh,
            onSelect = actions.setPreviewRefresh,
            index = 1,
            count = 3,
        )
        SwitchItem(
            title = stringResource(R.string.settings_save_data),
            description = stringResource(R.string.settings_save_data_hint),
            checked = settings.savePreviewData,
            onCheckedChange = actions.setSavePreviewData,
            index = 2,
            count = 3,
        )
    }
}

@Composable
private fun Servers(onManage: () -> Unit) {
    ListGroup(title = stringResource(R.string.servers_title)) {
        LinkItem(
            title = stringResource(R.string.settings_manage_servers),
            description = stringResource(R.string.settings_manage_servers_hint),
            onClick = onManage,
            index = 0,
            count = 1,
        )
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
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
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

@Composable
private fun SwitchItem(
    title: String,
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
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        // The whole row toggles; the switch only shows the state.
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun LinkItem(
    title: String,
    description: String,
    onClick: () -> Unit,
    index: Int,
    count: Int,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
    ) { Text(title, style = MaterialTheme.typography.titleSmall) }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    FrigateBridgeTheme {
        SettingsScreen(AppSettings(), SettingsActions())
    }
}
