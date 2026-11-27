package it.federicoraponi.watchdeck.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.CertificatePrompt
import it.federicoraponi.watchdeck.ui.ConnectionProblem
import it.federicoraponi.watchdeck.ui.FormError
import it.federicoraponi.watchdeck.ui.ServerFormArgs
import it.federicoraponi.watchdeck.ui.ServerFormState
import it.federicoraponi.watchdeck.ui.ServerFormViewModel
import it.federicoraponi.watchdeck.ui.designsystem.BackButton
import it.federicoraponi.watchdeck.ui.designsystem.FormField
import it.federicoraponi.watchdeck.ui.designsystem.GroupHint
import it.federicoraponi.watchdeck.ui.designsystem.ListGroup
import it.federicoraponi.watchdeck.ui.designsystem.LocalNetworkNotice
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.PasswordField
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.ScreenTitle
import it.federicoraponi.watchdeck.ui.designsystem.ServerBadge
import it.federicoraponi.watchdeck.ui.designsystem.ShapedIcon
import it.federicoraponi.watchdeck.ui.designsystem.SheetTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.problemText
import it.federicoraponi.watchdeck.ui.designsystem.rememberFullSheetState
import it.federicoraponi.watchdeck.ui.designsystem.serverColors
import it.federicoraponi.watchdeck.ui.rememberAllowLocalNetwork
import it.federicoraponi.watchdeck.ui.rememberLocalNetworkAccess
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class ServerFormActions(
    val close: () -> Unit = {},
    val setName: (String) -> Unit = {},
    val setUrl: (String) -> Unit = {},
    val setAlternativeUrl: (String) -> Unit = {},
    val setUsername: (String) -> Unit = {},
    val setPassword: (String) -> Unit = {},
    val setColor: (Int) -> Unit = {},
    val save: () -> Unit = {},
    val saveAnyway: () -> Unit = {},
    val acceptCertificate: () -> Unit = {},
    val rejectCertificate: () -> Unit = {},
    val allowLocalNetwork: () -> Unit = {},
)

@Composable
fun ServerFormRoute(
    serverId: String?,
    onClose: () -> Unit,
    viewModel: ServerFormViewModel = koinViewModel { parametersOf(ServerFormArgs(serverId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkAccess()
    val allowLocalNetwork = rememberAllowLocalNetwork(localNetwork, onAllowed = viewModel::save)
    LaunchedEffect(state.savedServerId) {
        if (state.savedServerId != null) onClose()
    }
    ServerFormScreen(
        state = state,
        localNetworkBlocked = !localNetwork.isGranted && state.problem == ConnectionProblem.Unreachable,
        actions =
            ServerFormActions(
                close = onClose,
                setName = viewModel::setName,
                setUrl = viewModel::setUrl,
                setAlternativeUrl = viewModel::setAlternativeUrl,
                setUsername = viewModel::setUsername,
                setPassword = viewModel::setPassword,
                setColor = viewModel::setColor,
                save = {
                    // A server given by name may be on the local network too, so ask before the first test.
                    // The test runs whatever the answer: a public server does not need the permission.
                    if (localNetwork.isGranted) viewModel.save() else localNetwork.request { viewModel.save() }
                },
                saveAnyway = viewModel::saveAnyway,
                acceptCertificate = viewModel::acceptCertificate,
                rejectCertificate = viewModel::rejectCertificate,
                allowLocalNetwork = allowLocalNetwork,
            ),
    )
}

@Composable
fun ServerFormScreen(
    state: ServerFormState,
    actions: ServerFormActions,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val title = if (state.isEditing) R.string.server_edit else R.string.server_add
            TopAppBar(
                title = { ScreenTitle(stringResource(title)) },
                navigationIcon = {
                    BackButton(actions.close)
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            ServerPreviewCard(state)
            ServerFields(state, actions)
            LoginFields(state, actions)
            ColorPicker(state.colorIndex, actions.setColor)
            if (state.sendsPasswordUnencrypted) NoticeCard(stringResource(R.string.form_cleartext_warning))
            state.problem?.let { NoticeCard(problemText(it), announce = true) }
            if (localNetworkBlocked) LocalNetworkNotice(actions.allowLocalNetwork)
            SaveButtons(state, actions)
        }
    }
    state.certificatePrompt?.let { CertificateSheet(it, actions) }
}

/** The hero of the screen: how the server will look in the list. */
@Composable
private fun ServerPreviewCard(state: ServerFormState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            val name = state.name.ifBlank { stringResource(R.string.server_new) }
            ServerBadge(name, state.colorIndex, size = 48.dp)
            Column {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.url.ifBlank { stringResource(R.string.form_url_example) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ServerFields(
    state: ServerFormState,
    actions: ServerFormActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val addressKeyboard =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next)
        ListGroup(title = stringResource(R.string.form_section_server)) {
            FormField(
                value = state.name,
                onValueChange = actions.setName,
                label = R.string.form_name,
                index = 0,
                count = 3,
                error = errorText(R.string.form_error_name, FormError.NameMissing in state.errors),
                keyboard =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            FormField(
                value = state.url,
                onValueChange = actions.setUrl,
                label = R.string.form_url,
                index = 1,
                count = 3,
                placeholder = R.string.form_url_example,
                error = errorText(R.string.form_error_url, FormError.UrlInvalid in state.errors),
                keyboard = addressKeyboard,
            )
            FormField(
                value = state.alternativeUrl,
                onValueChange = actions.setAlternativeUrl,
                label = R.string.form_alternative_url,
                index = 2,
                count = 3,
                error = errorText(R.string.form_error_url, FormError.AlternativeUrlInvalid in state.errors),
                keyboard = addressKeyboard,
            )
        }
        GroupHint(stringResource(R.string.form_alternative_url_hint))
    }
}

@Composable
private fun LoginFields(
    state: ServerFormState,
    actions: ServerFormActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ListGroup(title = stringResource(R.string.form_section_login)) {
            FormField(
                value = state.username,
                onValueChange = actions.setUsername,
                label = R.string.form_username,
                index = 0,
                count = 2,
                keyboard = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )
            PasswordField(
                label = R.string.form_password,
                index = 1,
                count = 2,
                onValueChange = actions.setPassword,
                error = errorText(R.string.form_error_password, FormError.PasswordMissing in state.errors),
                initial = state.password,
            )
        }
        GroupHint(
            stringResource(if (state.isEditing) R.string.form_password_keep else R.string.form_username_hint),
        )
    }
}

@Composable
private fun errorText(
    text: Int,
    isShown: Boolean,
): String? = if (isShown) stringResource(text) else null

/** The chosen colour turns from a circle into an Expressive shape with a check mark. */
@Composable
private fun ColorPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    ListGroup(title = stringResource(R.string.form_color)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup().padding(Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                serverColors.forEachIndexed { index, color ->
                    val label = stringResource(R.string.form_color_option, index + 1)
                    val isSelected = index == selected
                    val shape = if (isSelected) MaterialShapes.Cookie9Sided.toShape() else CircleShape
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(index) },
                                ).semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(if (isSelected) 40.dp else 28.dp)
                                    .clip(shape)
                                    .background(color),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(Symbols.Check, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveButtons(
    state: ServerFormState,
    actions: ServerFormActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PrimaryButton(
            text = stringResource(if (state.isSaving) R.string.form_testing else R.string.form_save),
            onClick = actions.save,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
            leading =
                if (state.isSaving) {
                    { LoadingIndicator(modifier = Modifier.size(24.dp)) }
                } else {
                    null
                },
        )
        if (state.canSaveAnyway) {
            FilledTonalButton(
                onClick = actions.saveAnyway,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.form_save_anyway))
            }
        }
    }
}

@Composable
private fun CertificateSheet(
    prompt: CertificatePrompt,
    actions: ServerFormActions,
) {
    val title =
        if (prompt.replacesAccepted) R.string.certificate_changed_title else R.string.certificate_title
    val message =
        if (prompt.replacesAccepted) R.string.certificate_changed_message else R.string.certificate_message
    ModalBottomSheet(
        onDismissRequest = actions.rejectCertificate,
        sheetState = rememberFullSheetState(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl)
                    .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShapedIcon(Symbols.Lock, shape = MaterialShapes.SoftBurst)
            SheetTitle(stringResource(title), textAlign = TextAlign.Center)
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        stringResource(R.string.certificate_fingerprint),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        prompt.sha256Fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.certificate_trust),
                onClick = actions.acceptCertificate,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = actions.rejectCertificate,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Preview
@Composable
private fun ServerFormScreenPreview() {
    WatchdeckTheme {
        ServerFormScreen(
            state = ServerFormState(name = "Home", url = "http://192.168.1.20:5000"),
            actions = ServerFormActions(),
        )
    }
}
