package it.federicoraponi.watchdeck.ui

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Long enough for the progress indicator to be seen, also when the server answers at once.
private const val MINIMUM_REFRESH_MILLIS = 600L

/** Runs [reload] and returns no sooner than the indicator needs to appear and leave cleanly. */
internal suspend fun visibleRefresh(reload: suspend () -> Unit) {
    coroutineScope {
        launch { delay(MINIMUM_REFRESH_MILLIS) }
        reload()
    }
}
