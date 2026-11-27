package it.federicoraponi.watchdeck.ui.lock

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.designsystem.MessageState
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme

/** Shown instead of the app until the user proves to be the phone's owner. Asks once by itself. */
@Composable
fun LockScreen(
    problem: String?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var asked by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (!asked) {
            asked = true
            onUnlock()
        }
        onPauseOrDispose {}
    }
    Scaffold(modifier = modifier) { innerPadding ->
        MessageState(
            icon = Symbols.Lock,
            title = stringResource(R.string.lock_title),
            message = problem,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            PrimaryButton(text = stringResource(R.string.lock_unlock), onClick = onUnlock)
        }
    }
}

@Preview
@Composable
private fun LockScreenPreview() {
    WatchdeckTheme { LockScreen(problem = null, onUnlock = {}) }
}
