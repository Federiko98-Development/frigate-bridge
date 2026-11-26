package it.federicoraponi.frigatebridge.ui.camera

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.RecordingClip
import it.federicoraponi.frigatebridge.server.acceptingCertificate
import it.federicoraponi.frigatebridge.ui.SeekRequest
import it.federicoraponi.frigatebridge.ui.capture.ProvidesPlayerFrames
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

private const val POSITION_INTERVAL_MILLIS = 500L

/**
 * A player for a stretch of recordings, that downloads it with the server's token and accepted certificate.
 * It moves where [seek] asks, and reports where it is while playing.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberRecordingPlayer(
    clip: RecordingClip,
    seek: SeekRequest,
    speed: Float,
    onPosition: (Long) -> Unit,
    onEnded: () -> Unit,
    onError: (String) -> Unit,
): ExoPlayer {
    val context = LocalContext.current
    val position by rememberUpdatedState(onPosition)
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    val player =
        remember(clip) {
            val http = OkHttpClient.Builder().acceptingCertificate(clip.certificateSha256).build()
            val data = OkHttpDataSource.Factory(http).setDefaultRequestProperties(clip.headers)
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(data))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(clip.url), seek.positionMillis)
                    prepare()
                    playWhenReady = true
                }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) ended()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failed(listOfNotNull(error.errorCodeName, error.cause?.message).joinToString(": "))
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, seek.id) {
        player.seekTo(seek.positionMillis)
        player.play()
    }
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(player) {
        while (true) {
            delay(POSITION_INTERVAL_MILLIS)
            if (player.isPlaying) position(player.currentPosition)
        }
    }
    LifecycleStartEffect(player) { onStopOrDispose { player.pause() } }
    return player
}

@OptIn(UnstableApi::class)
@Composable
fun RecordingPicture(
    player: Player,
    modifier: Modifier = Modifier,
) {
    // A texture view moves with the screen during page transitions.
    ContentFrame(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = modifier)
    ProvidesPlayerFrames(player)
}

/** Moves [seconds] back or forward, not before the start. */
fun Player.seekBy(seconds: Int) = seekTo((currentPosition + seconds * 1_000L).coerceAtLeast(0))

/** Play and pause. */
@Composable
fun PlayButton(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    var playing by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (player != null) {
            // What the user asked for, also while the video is still loading.
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            delay(POSITION_INTERVAL_MILLIS / 2)
        }
    }
    FilledIconButton(
        enabled = player != null,
        onClick = {
            if (player == null) return@FilledIconButton
            if (playing) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            playing = !playing
        },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.size(56.dp),
    ) {
        if (playing) {
            Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.video_pause))
        } else {
            Icon(Icons.Filled.PlayArrow, stringResource(R.string.video_play))
        }
    }
}

/** Play and pause with jumps of ten seconds and a minute. With large fonts the jumps wrap to another line. */
@Composable
fun RecordingTransport(
    player: Player?,
    onSkip: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(-60) { onSkip(-60) }
        SkipButton(-10) { onSkip(-10) }
        PlayButton(player)
        SkipButton(10) { onSkip(10) }
        SkipButton(60) { onSkip(60) }
    }
}

/** A jump of [seconds], back or forward: ten seconds or a minute. */
@Composable
fun SkipButton(
    seconds: Int,
    onClick: () -> Unit,
) {
    val (label, description) =
        when (seconds) {
            -60 -> R.string.video_back_60 to R.string.skip_back_60
            -10 -> R.string.video_back_10 to R.string.skip_back_10
            10 -> R.string.video_forward_10 to R.string.skip_forward_10
            else -> R.string.video_forward_60 to R.string.skip_forward_60
        }
    val spoken = stringResource(description)
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
        // "−1 m" read aloud says less than "back 1 minute".
        modifier = Modifier.semantics { contentDescription = spoken },
    ) { Text(stringResource(label), modifier = Modifier.clearAndSetSemantics {}) }
}
