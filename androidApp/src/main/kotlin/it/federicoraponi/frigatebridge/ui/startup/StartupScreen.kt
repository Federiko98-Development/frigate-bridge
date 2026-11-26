package it.federicoraponi.frigatebridge.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.startup.StartupState
import it.federicoraponi.frigatebridge.startup.StartupViewModel
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

/** Connects the shared ViewModel to the screen. */
@Composable
fun StartupRoute(viewModel: StartupViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StartupScreen(state = state, onTap = viewModel::onTap)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StartupScreen(
    state: StartupState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.startup_platform, state.platformName),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.startup_tap_count, state.tapCount))
            Button(onClick = onTap, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.startup_tap))
            }
        }
    }
}

@Preview
@Composable
private fun StartupScreenPreview() {
    FrigateBridgeTheme {
        StartupScreen(state = StartupState(platformName = "Android 16", tapCount = 3), onTap = {})
    }
}
