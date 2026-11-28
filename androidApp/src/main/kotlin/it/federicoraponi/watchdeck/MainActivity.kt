package it.federicoraponi.watchdeck

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.watchdeck.auth.OidcSignIn
import it.federicoraponi.watchdeck.security.AppLockPrompt
import it.federicoraponi.watchdeck.settings.ThemeMode
import it.federicoraponi.watchdeck.ui.LockPurpose
import it.federicoraponi.watchdeck.ui.LockViewModel
import it.federicoraponi.watchdeck.ui.SettingsViewModel
import it.federicoraponi.watchdeck.ui.StartViewModel
import it.federicoraponi.watchdeck.ui.cameras.LocalGroupButtons
import it.federicoraponi.watchdeck.ui.lock.LockScreen
import it.federicoraponi.watchdeck.ui.navigation.AppNavigation
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    // The one the navigation observes: both belong to the activity.
    private val start: StartViewModel by viewModel()
    private val lock: LockViewModel by viewModel()
    private val signIn: OidcSignIn by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A link already followed comes back with a recreated activity, and from the list of recent apps.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) openLink(intent)
        val prompt = AppLockPrompt(this, lock)

        setContent {
            val viewModel: SettingsViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            // Nothing is drawn until the settings are read, so that the chosen theme does not flash in.
            val settings = state.settings ?: return@setContent
            val dark =
                when (settings.themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            // The system bar icons follow the app's theme, which may differ from the phone's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val lockState by lock.state.collectAsStateWithLifecycle()
            // With the lock on, the list of recent apps shows no picture of the cameras.
            LaunchedEffect(lockState.isEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!lockState.isEnabled)
                }
            }
            // The app is not merely covered while locked: nothing of it runs, and it comes back where it was.
            val kept = rememberSaveableStateHolder()
            WatchdeckTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                when (lockState.isLocked) {
                    null -> Unit
                    true -> LockScreen(lockState.problem, onUnlock = { prompt.ask(LockPurpose.Unlock) })
                    false ->
                        CompositionLocalProvider(LocalGroupButtons provides settings.groupButtons) {
                            kept.SaveableStateProvider("app") {
                                AppNavigation(
                                    viewModel = start,
                                    onAppLockChange = { enabled ->
                                        prompt.ask(if (enabled) LockPurpose.Enable else LockPurpose.Disable)
                                    },
                                )
                            }
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the sign-in tab without its answer, which would have arrived before this.
        signIn.returnedWithoutAnswer()
    }

    override fun onStart() {
        lock.appShown(SystemClock.elapsedRealtime())
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) lock.appHidden(SystemClock.elapsedRealtime())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(start::openLink)
    }
}
