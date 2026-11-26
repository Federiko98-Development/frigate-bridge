package it.federicoraponi.frigatebridge.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.StartDestination
import it.federicoraponi.frigatebridge.ui.StartViewModel
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.camera.CameraRoute
import it.federicoraponi.frigatebridge.ui.cameras.CamerasRoute
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.recordings.RecordingsRoute
import it.federicoraponi.frigatebridge.ui.servers.ServerFormRoute
import it.federicoraponi.frigatebridge.ui.servers.ServerListRoute
import it.federicoraponi.frigatebridge.ui.settings.SettingsRoute
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
private data object Servers

@Serializable
private data object Cameras

@Serializable
private data object Recordings

@Serializable
private data object SettingsTab

/** The settings opened from the server list, outside any server. */
@Serializable
private data object SettingsPage

@Serializable
private data class CameraView(
    val serverId: String,
    val cameraName: String,
    val recording: Boolean = false,
)

@Serializable
private data class ServerForm(
    val serverId: String? = null,
)

/** Material "emphasized" easing. Springs would overshoot visibly on a full-screen slide. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun <T> pageTween(easing: Easing = Emphasized) = tween<T>(durationMillis = 400, easing = easing)

/** The screen underneath moves a quarter of the width and is slightly dimmed while covered. */
private const val COVERED_ALPHA = 0.7f

private fun popEnter(easing: Easing = Emphasized): EnterTransition =
    slideInHorizontally(pageTween(easing)) { -it / 4 } +
        fadeIn(pageTween(easing), initialAlpha = COVERED_ALPHA)

private fun popExit(easing: Easing = Emphasized): ExitTransition = slideOutHorizontally(pageTween(easing)) { it }

// Between tabs the content only fades: they are side by side, and no screen should ever change size.
private fun tabEnter(): EnterTransition = fadeIn(tween(durationMillis = 210, delayMillis = 90))

private fun tabExit(): ExitTransition = fadeOut(tween(durationMillis = 90))

private val NavBackStackEntry.isTab: Boolean
    get() =
        destination.hasRoute<Cameras>() || destination.hasRoute<Recordings>() || destination.hasRoute<SettingsTab>()

private val AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs: Boolean
    get() = initialState.isTab && targetState.isTab

@Composable
fun AppNavigation(viewModel: StartViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination = state.destination
    if (destination == null) {
        LoadingContent()
    } else {
        AppNavHost(destination)
    }
}

@Composable
private fun AppNavHost(destination: StartDestination) {
    val navController = rememberNavController()
    val start: Any = remember { if (destination == StartDestination.Cameras) Cameras else Servers }

    val tabBar: @Composable (MainTab) -> Unit = { selected ->
        MainTabBar(selected, onSelect = navController::openTab)
    }
    NavHost(
        navController = navController,
        startDestination = start,
        // One horizontal slide for forward, back and the back gesture; a fade between tabs.
        enterTransition = { if (betweenTabs) tabEnter() else slideInHorizontally(pageTween()) { it } },
        exitTransition = {
            if (betweenTabs) {
                tabExit()
            } else {
                slideOutHorizontally(pageTween()) { -it / 4 } + fadeOut(pageTween(), targetAlpha = COVERED_ALPHA)
            }
        },
        popEnterTransition = { if (betweenTabs) tabEnter() else popEnter() },
        popExitTransition = { if (betweenTabs) tabExit() else popExit() },
        // Without these the library shrinks the screen during the back gesture. Linear, so it follows the finger.
        predictivePopEnterTransition = { if (betweenTabs) tabEnter() else popEnter(LinearEasing) },
        predictivePopExitTransition = { if (betweenTabs) tabExit() else popExit(LinearEasing) },
    ) {
        composable<Servers> {
            ServerListRoute(
                onOpenCameras = { navController.navigate(Cameras) { launchSingleTop = true } },
                onAddServer = { navController.navigate(ServerForm()) { launchSingleTop = true } },
                onEditServer = { navController.navigate(ServerForm(it)) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(SettingsPage) { launchSingleTop = true } },
            )
        }
        composable<Cameras> {
            CamerasRoute(
                onShowServers = { navController.showServers() },
                onEditServer = { navController.navigate(ServerForm(it)) { launchSingleTop = true } },
                onOpenCamera = { navController.openCamera(it, recording = false) },
                bottomBar = { tabBar(MainTab.Cameras) },
            )
        }
        composable<Recordings> {
            RecordingsRoute(
                onShowServers = { navController.showServers() },
                onOpenRecording = { navController.openCamera(it, recording = true) },
                bottomBar = { tabBar(MainTab.Recordings) },
            )
        }
        composable<SettingsTab> {
            SettingsRoute(
                onManageServers = { navController.showServers() },
                bottomBar = { tabBar(MainTab.Settings) },
            )
        }
        composable<SettingsPage> {
            SettingsRoute(
                onManageServers = { navController.popBackStack<SettingsPage>(inclusive = true) },
                onBack = { navController.popBackStack<SettingsPage>(inclusive = true) },
            )
        }
        composable<CameraView> { entry ->
            val route = entry.toRoute<CameraView>()
            CameraRoute(
                camera = CameraRef(route.serverId, route.cameraName),
                mode = if (route.recording) VideoMode.Recording else VideoMode.Live,
                onClose = { navController.popBackStack<CameraView>(inclusive = true) },
            )
        }
        composable<ServerForm> { entry ->
            ServerFormRoute(
                serverId = entry.toRoute<ServerForm>().serverId,
                onClose = { navController.popBackStack<ServerForm>(inclusive = true) },
            )
        }
    }
}

private fun NavHostController.openCamera(
    camera: CameraRef,
    recording: Boolean,
) {
    navigate(CameraView(camera.serverId, camera.cameraName, recording)) { launchSingleTop = true }
}

/** Moves between tabs, keeping the cameras underneath so that back always returns to them. */
private fun NavHostController.openTab(tab: MainTab) {
    if (tab == MainTab.Cameras) {
        popBackStack<Cameras>(inclusive = false)
        return
    }
    val route: Any = if (tab == MainTab.Recordings) Recordings else SettingsTab
    navigate(route) {
        popUpTo<Cameras> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Goes back to the server list, putting it in place of the cameras when the app opened straight on them. */
private fun NavHostController.showServers() {
    if (!popBackStack<Servers>(inclusive = false)) {
        navigate(Servers) { popUpTo<Cameras> { inclusive = true } }
    }
}
