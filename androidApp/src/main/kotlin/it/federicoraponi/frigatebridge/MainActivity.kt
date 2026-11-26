package it.federicoraponi.frigatebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import it.federicoraponi.frigatebridge.ui.navigation.AppNavigation
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            FrigateBridgeTheme {
                AppNavigation()
            }
        }
    }
}
