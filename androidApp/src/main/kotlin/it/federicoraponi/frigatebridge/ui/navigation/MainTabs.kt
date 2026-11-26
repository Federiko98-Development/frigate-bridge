package it.federicoraponi.frigatebridge.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import it.federicoraponi.frigatebridge.R

/** The screens of the bottom bar. They all show the active server. */
enum class MainTab {
    Cameras,
    Events,
    Settings,
}

@Composable
fun MainTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    ShortNavigationBar {
        MainTab.entries.forEach { tab ->
            val label =
                stringResource(
                    when (tab) {
                        MainTab.Cameras -> R.string.tab_cameras
                        MainTab.Events -> R.string.tab_events
                        MainTab.Settings -> R.string.settings_title
                    },
                )
            ShortNavigationBarItem(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                // The label under the icon names the tab.
                icon = {
                    when (tab) {
                        MainTab.Cameras -> Icon(painterResource(R.drawable.ic_videocam), contentDescription = null)
                        MainTab.Events -> Icon(Icons.Filled.Notifications, contentDescription = null)
                        MainTab.Settings -> Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                },
                label = { Text(label) },
            )
        }
    }
}
