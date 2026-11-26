package it.federicoraponi.frigatebridge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.settings.ThemeMode
import it.federicoraponi.frigatebridge.ui.SettingsViewModel
import it.federicoraponi.frigatebridge.ui.navigation.AppNavigation
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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
            FrigateBridgeTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppNavigation()
            }
        }
    }
}
