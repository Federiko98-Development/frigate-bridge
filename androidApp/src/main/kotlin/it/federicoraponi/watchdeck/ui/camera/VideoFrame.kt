package it.federicoraponi.watchdeck.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.VideoFailure
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.problemText

/** The black 16:9 area that every video and its messages share. */
@Composable
internal fun VideoFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.large)
                .background(Color.Black),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A message in place of the picture. [announce] it when it replaces the picture on its own, e.g. a failure. */
@Composable
internal fun OverVideo(
    text: String,
    announce: Boolean = false,
) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.padding(Spacing.lg).semantics {
                if (announce) liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
internal fun Failure(
    failure: VideoFailure,
    fallback: Int,
    onRetry: () -> Unit,
    isOffline: Boolean = false,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val problem = failure.problem
        val text =
            when {
                isOffline -> stringResource(R.string.offline_title)
                problem != null -> problemText(problem)
                else -> stringResource(fallback)
            }
        OverVideo(text, announce = true)
        failure.detail?.takeUnless { isOffline }?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 3)
        }
        PrimaryButton(text = stringResource(R.string.retry), onClick = onRetry)
    }
}

@Composable
internal fun FullscreenButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.symbol_fullscreen), contentDescription = null)
        Text(stringResource(R.string.video_fullscreen), modifier = Modifier.padding(start = Spacing.sm))
    }
}
