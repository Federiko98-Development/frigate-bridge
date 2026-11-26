package it.federicoraponi.frigatebridge.ui.navigation

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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.StartDestination
import it.federicoraponi.frigatebridge.ui.StartViewModel
import it.federicoraponi.frigatebridge.ui.camera.CameraRoute
import it.federicoraponi.frigatebridge.ui.cameras.CamerasRoute
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.servers.ServerFormRoute
import it.federicoraponi.frigatebridge.ui.servers.ServerListRoute
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
private data object Servers

@Serializable
private data object Cameras

@Serializable
private data class CameraView(
    val serverId: String,
    val cameraName: String,
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
    NavHost(
        navController = navController,
        startDestination = start,
        // One horizontal slide for forward, back and the back gesture.
        enterTransition = { slideInHorizontally(pageTween()) { it } },
        exitTransition = {
            slideOutHorizontally(pageTween()) { -it / 4 } + fadeOut(pageTween(), targetAlpha = COVERED_ALPHA)
        },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
        // Without these the library shrinks the screen during the back gesture. Linear, so it follows the finger.
        predictivePopEnterTransition = { popEnter(LinearEasing) },
        predictivePopExitTransition = { popExit(LinearEasing) },
    ) {
        composable<Servers> {
            ServerListRoute(
                onOpenCameras = { navController.navigate(Cameras) { launchSingleTop = true } },
                onAddServer = { navController.navigate(ServerForm()) { launchSingleTop = true } },
                onEditServer = { navController.navigate(ServerForm(it)) { launchSingleTop = true } },
            )
        }
        composable<Cameras> {
            CamerasRoute(
                onShowServers = { navController.showServers() },
                onEditServer = { navController.navigate(ServerForm(it)) { launchSingleTop = true } },
                onOpenCamera = {
                    navController.navigate(CameraView(it.serverId, it.cameraName)) { launchSingleTop = true }
                },
            )
        }
        composable<CameraView> { entry ->
            val route = entry.toRoute<CameraView>()
            CameraRoute(
                camera = CameraRef(route.serverId, route.cameraName),
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

/** Goes back to the server list, putting it in place of the cameras when the app opened straight on them. */
private fun NavHostController.showServers() {
    if (!popBackStack<Servers>(inclusive = false)) {
        navigate(Servers) { popUpTo<Cameras> { inclusive = true } }
    }
}
