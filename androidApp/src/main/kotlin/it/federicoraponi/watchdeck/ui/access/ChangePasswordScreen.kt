package it.federicoraponi.watchdeck.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.server.AccessRules
import it.federicoraponi.watchdeck.server.PasswordProblem
import it.federicoraponi.watchdeck.ui.AdminFailure
import it.federicoraponi.watchdeck.ui.ChangePasswordState
import it.federicoraponi.watchdeck.ui.ChangePasswordViewModel
import it.federicoraponi.watchdeck.ui.designsystem.BackButton
import it.federicoraponi.watchdeck.ui.designsystem.GroupHint
import it.federicoraponi.watchdeck.ui.designsystem.ListGroup
import it.federicoraponi.watchdeck.ui.designsystem.LoadingContent
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.PasswordField
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.ScreenTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.adminFailureText
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
import org.koin.androidx.compose.koinViewModel

/** The signed-in user's own password. */
@Composable
fun ChangePasswordRoute(
    onClose: () -> Unit,
    viewModel: ChangePasswordViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    ChangePasswordScreen(state, onClose = onClose, onSave = viewModel::save)
}

@Composable
fun ChangePasswordScreen(
    state: ChangePasswordState,
    onClose: () -> Unit,
    /** The current password, the new one and its confirmation. */
    onSave: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = remember { SnackbarHostState() }
    val done = stringResource(R.string.password_changed)
    val close by rememberUpdatedState(onClose)
    // The page stays until the message has been shown: it would not be seen after leaving.
    LaunchedEffect(state.isDone) {
        if (state.isDone) {
            messages.showSnackbar(done)
            close()
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.password_change_title)) },
                navigationIcon = { BackButton(onClose) },
            )
        },
    ) { innerPadding ->
        // Until the server says who is signed in, or why it cannot.
        // Not while saving: that would take the form away, and with it what was typed.
        if (state.username.isEmpty() && state.failure == null && !state.isSaving) {
            LoadingContent(Modifier.padding(innerPadding))
        } else {
            PasswordForm(
                state = state,
                onSave = onSave,
                modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
            )
        }
    }
}

@Composable
private fun PasswordForm(
    state: ChangePasswordState,
    onSave: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Not saveable on purpose, like the fields themselves.
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val isWrongCurrent = state.failure == AdminFailure.WrongCurrentPassword
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ListGroup(title = state.username.ifEmpty { state.serverName }) {
                PasswordField(
                    label = R.string.password_current,
                    index = 0,
                    count = 3,
                    onValueChange = { current = it },
                    error =
                        when {
                            state.password == PasswordProblem.CurrentMissing ->
                                stringResource(R.string.password_current_missing)
                            isWrongCurrent -> stringResource(R.string.password_wrong_current)
                            else -> null
                        },
                )
                PasswordField(
                    label = R.string.password_new,
                    index = 1,
                    count = 3,
                    onValueChange = { new = it },
                    error =
                        if (state.password == PasswordProblem.TooShort) {
                            stringResource(R.string.password_too_short, AccessRules.MIN_PASSWORD)
                        } else {
                            null
                        },
                )
                PasswordField(
                    label = R.string.password_confirm_new,
                    index = 2,
                    count = 3,
                    onValueChange = { confirmation = it },
                    error =
                        if (state.password == PasswordProblem.Mismatch) {
                            stringResource(R.string.password_mismatch)
                        } else {
                            null
                        },
                )
            }
            GroupHint(stringResource(R.string.password_change_hint, AccessRules.MIN_PASSWORD))
        }
        state.failure?.takeUnless { isWrongCurrent }?.let { NoticeCard(adminFailureText(it), announce = true) }
        if (state.isSaving) {
            ContainedLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (!state.isDone) {
            PrimaryButton(
                text = stringResource(R.string.password_change_title),
                onClick = { onSave(current, new, confirmation) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview
@Composable
private fun ChangePasswordScreenPreview() {
    WatchdeckTheme {
        ChangePasswordScreen(
            state = ChangePasswordState(serverName = "Home", username = "alice"),
            onClose = {},
            onSave = { _, _, _ -> },
        )
    }
}
