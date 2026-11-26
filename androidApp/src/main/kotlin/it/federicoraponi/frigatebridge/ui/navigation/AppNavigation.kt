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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.EventTarget
import it.federicoraponi.frigatebridge.ui.StartDestination
import it.federicoraponi.frigatebridge.ui.StartViewModel
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.camera.CameraRoute
import it.federicoraponi.frigatebridge.ui.cameras.CamerasRoute
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.events.EventRoute
import it.federicoraponi.frigatebridge.ui.events.EventsRoute
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
private data object Events

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
    /** The moment of the recordings to start from, or 0 for the latest. */
    val startTime: Long = 0,
)

@Serializable
private data class EventView(
    val serverId: String,
    val reviewId: String,
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
        listOf(Cameras::class, Events::class, SettingsTab::class).any { destination.hasRoute(it) }

private val AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs: Boolean
    get() = initialState.isTab && targetState.isTab

@Composable
fun AppNavigation(
    viewModel: StartViewModel = koinViewModel(),
    /** The user wants the app lock turned on or off: it takes effect once they prove who they are. */
    onAppLockChange: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination = state.destination
    if (destination == null) {
        LoadingContent()
    } else {
        AppNavHost(destination, state.target, viewModel::linkHandled, onAppLockChange)
    }
    if (state.isLinkRejected) {
        AlertDialog(
            onDismissRequest = viewModel::linkHandled,
            title = { Text(stringResource(R.string.link_rejected_title)) },
            text = { Text(stringResource(R.string.link_rejected_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::linkHandled, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun AppNavHost(
    destination: StartDestination,
    target: EventTarget?,
    onLinkHandled: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
) {
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
        composable<Events> {
            EventsRoute(
                onShowServers = { navController.showServers() },
                onOpen = { moment ->
                    navController.navigate(EventView(moment.serverId, moment.id)) { launchSingleTop = true }
                },
                bottomBar = { tabBar(MainTab.Events) },
            )
        }
        composable<SettingsTab> {
            SettingsRoute(
                onManageServers = { navController.showServers() },
                onAppLockChange = onAppLockChange,
                bottomBar = { tabBar(MainTab.Settings) },
            )
        }
        composable<SettingsPage> {
            SettingsRoute(
                onManageServers = { navController.popBackStack<SettingsPage>(inclusive = true) },
                onAppLockChange = onAppLockChange,
                onBack = { navController.popBackStack<SettingsPage>(inclusive = true) },
            )
        }
        composable<CameraView> { entry ->
            val route = entry.toRoute<CameraView>()
            CameraRoute(
                camera = CameraRef(route.serverId, route.cameraName),
                mode = if (route.recording) VideoMode.Recording else VideoMode.Live,
                startTime = route.startTime.takeIf { it > 0 },
                onClose = { navController.popBackStack<CameraView>(inclusive = true) },
            )
        }
        composable<EventView> { entry ->
            val route = entry.toRoute<EventView>()
            EventRoute(
                serverId = route.serverId,
                reviewId = route.reviewId,
                onClose = { navController.popBackStack<EventView>(inclusive = true) },
                onOpenTimeline = { camera, startTime ->
                    navController.openCamera(camera, recording = true, startTime = startTime)
                },
            )
        }
        composable<ServerForm> { entry ->
            ServerFormRoute(
                serverId = entry.toRoute<ServerForm>().serverId,
                onClose = { navController.popBackStack<ServerForm>(inclusive = true) },
            )
        }
    }
    LaunchedEffect(target) {
        if (target != null) {
            navController.openLinkedEvent(target)
            onLinkHandled()
        }
    }
}

/** Shows the moment a link asks for, with the cameras of its server underneath so that back leads there. */
private fun NavHostController.openLinkedEvent(target: EventTarget) {
    if (!popBackStack<Cameras>(inclusive = false)) navigate(Cameras)
    navigate(EventView(target.serverId, target.reviewId))
}

private fun NavHostController.openCamera(
    camera: CameraRef,
    recording: Boolean,
    startTime: Long = 0,
) {
    navigate(CameraView(camera.serverId, camera.cameraName, recording, startTime)) { launchSingleTop = true }
}

/** Moves between tabs, keeping the cameras underneath so that back always returns to them. */
private fun NavHostController.openTab(tab: MainTab) {
    if (tab == MainTab.Cameras) {
        popBackStack<Cameras>(inclusive = false)
        return
    }
    val route: Any =
        when (tab) {
            MainTab.Events -> Events
            else -> SettingsTab
        }
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
