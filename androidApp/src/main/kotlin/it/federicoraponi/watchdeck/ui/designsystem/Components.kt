package it.federicoraponi.watchdeck.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.ConnectionProblem
import it.federicoraponi.watchdeck.ui.VideoFailure

// Container-first layout: content sits in tonal, generously rounded containers, without shadows or dividers.

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** The colours a server can be marked with, indexed by `Server.colorIndex`. */
val serverColors =
    listOf(
        Color(0xFF3F6DB5),
        Color(0xFF2E7D5B),
        Color(0xFFB3542E),
        Color(0xFF7A52A8),
        Color(0xFFA83D62),
        Color(0xFF6B6F2A),
    )

fun serverColor(index: Int): Color = serverColors[index.mod(serverColors.size)]

/** The server's initial in an Expressive shape of its colour. Decorative: the name is written next to it. */
@Composable
fun ServerBadge(
    name: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(serverColor(colorIndex))
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
    }
}

/** Said above content that is no longer being updated. */
@Composable
fun OfflineNotice(modifier: Modifier = Modifier) {
    NoticeCard(
        title = stringResource(R.string.offline_title),
        message = stringResource(R.string.offline_message),
        modifier = modifier,
        announce = true,
    )
}

/** An icon centred in an Expressive polygon. */
@Composable
fun ShapedIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    size: Dp = 64.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = modifier.size(size).clip(shape.toShape()).background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size * 0.45f))
    }
}

/** The title of a top app bar. One line: the bar has a fixed height, also with large fonts. */
@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLargeEmphasized,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() },
    )
}

/** The way back, at the start of a top app bar. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}

/** The title of a bottom sheet. */
@Composable
fun SheetTitle(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleLargeEmphasized,
        textAlign = textAlign,
        modifier = modifier.semantics { heading() },
    )
}

/** The state of a bottom sheet that opens fully and has no half-open stop. */
@Composable
fun rememberFullSheetState(confirmValueChange: (SheetValue) -> Boolean = { true }): SheetState =
    rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = confirmValueChange,
    )

/**
 * A full bottom sheet that cannot be dismissed while [locked], e.g. while what it asked for is on its way: not by
 * dragging it, nor by tapping outside or going back.
 */
@Composable
fun LockableSheet(
    locked: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isLocked by rememberUpdatedState(locked)
    val confirm = remember { { value: SheetValue -> value != SheetValue.Hidden || !isLocked } }
    ModalBottomSheet(
        onDismissRequest = { if (!isLocked) onDismiss() },
        sheetState = rememberFullSheetState(confirm),
        sheetGesturesEnabled = !locked,
        properties =
            ModalBottomSheetProperties(
                shouldDismissOnBackPress = !locked,
                shouldDismissOnClickOutside = !locked,
            ),
        content = content,
    )
}

/**
 * Asks before something that cannot be undone, with room to explain what it does. A caller that keeps the sheet
 * open while the thing is done says so with [running], and why it did not work with [error].
 */
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    running: Boolean = false,
    error: String? = null,
) {
    LockableSheet(locked = running, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl)
                    .padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            SheetTitle(title, textAlign = TextAlign.Center)
            Text(message, textAlign = TextAlign.Center)
            if (error != null) NoticeCard(error, announce = true)
            if (running) {
                ContainedLoadingIndicator()
            } else {
                Button(
                    onClick = onConfirm,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(confirmLabel) }
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

/** Keeps the screen on while this is shown, e.g. while a video is being watched. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A titled group of segmented items. */
@Composable
fun ListGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Spacing.lg, bottom = Spacing.sm).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), content = content)
    }
}

/**
 * A tonal block with an icon and a message, optionally with one action. [announce] it when it appears on its own,
 * e.g. after something failed, so that screen readers say it without the user looking for it.
 */
@Composable
fun NoticeCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    announce: Boolean = false,
) {
    Surface(
        // The texts are read together; the action stays a node of its own.
        modifier =
            modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                if (announce) liveRegion = LiveRegionMode.Polite
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Icon(Symbols.Warning, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
                Text(message, style = MaterialTheme.typography.bodySmall)
                if (actionLabel != null) {
                    TextButton(
                        onClick = onAction,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.align(Alignment.End),
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                    ) { Text(actionLabel) }
                }
            }
        }
    }
}

/** The button used for the main action of a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text)
    }
}

/** A full-screen empty or error state: a shaped icon, what happened, and what to do next. */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    isError: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapedIcon(
            icon = icon,
            size = 80.dp,
            shape = if (isError) MaterialShapes.SoftBurst else MaterialShapes.Cookie9Sided,
            containerColor = if (isError) colors.errorContainer else colors.primaryContainer,
            contentColor = if (isError) colors.onErrorContainer else colors.onPrimaryContainer,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        actions()
    }
}

@Composable
fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator()
    }
}

@Composable
fun LocalNetworkNotice(
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoticeCard(
        title = stringResource(R.string.local_network_title),
        message = stringResource(R.string.local_network_message),
        actionLabel = stringResource(R.string.local_network_allow),
        onAction = onAllow,
        modifier = modifier,
        announce = true,
    )
}

/** Why a server cannot be used: without a network, that says more than whatever failed because of it. */
@Composable
fun unavailableText(
    problem: ConnectionProblem,
    isOffline: Boolean,
): String = if (isOffline) stringResource(R.string.offline_title) else problemText(problem)

/** What went wrong: the [problem] if it is known, otherwise [fallback], followed by the server's own [detail]. */
@Composable
fun failureText(
    failure: VideoFailure,
    fallback: Int,
): String {
    val problem = failure.problem
    val text = if (problem != null) problemText(problem) else stringResource(fallback)
    return listOfNotNull(text, failure.detail).joinToString("\n")
}

/** A status that changes on its own, such as the progress of an export, said again by screen readers. */
@Composable
fun StatusText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
fun problemText(problem: ConnectionProblem): String =
    stringResource(
        when (problem) {
            ConnectionProblem.Unreachable -> R.string.problem_unreachable
            ConnectionProblem.LoginRequired -> R.string.problem_login_required
            ConnectionProblem.LoginRejected -> R.string.problem_login_rejected
            ConnectionProblem.RateLimited -> R.string.problem_rate_limited
            ConnectionProblem.UntrustedCertificate -> R.string.problem_certificate
            ConnectionProblem.UnexpectedResponse -> R.string.problem_unexpected
        },
    )
