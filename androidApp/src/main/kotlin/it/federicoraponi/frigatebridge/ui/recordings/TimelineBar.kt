package it.federicoraponi.frigatebridge.ui.recordings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.recordings.RecordingDay
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.recordings.TimeRange
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How much time the width of the timeline shows. */
enum class TimelineZoom(
    val spanSeconds: Long,
    val tickSeconds: Long,
) {
    Minutes(900, 180),
    Hour(3_600, 600),
    Hours(21_600, 3_600),
}

private const val PREVIEW_PAUSE_MILLIS = 250L

// A moment shorter than this would be too thin to see.
private val MIN_MARK_WIDTH = 3.dp

/**
 * The day as a strip that slides under a fixed pointer: recorded stretches, with the review moments on top.
 * Dragging shows the time and a picture of it; the video moves there on release. A tap goes straight to a point.
 */
@Composable
fun TimelineBar(
    day: RecordingDay,
    ranges: List<TimeRange>,
    markers: List<ReviewItem>,
    time: Long,
    zoom: TimelineZoom,
    onSeek: (Long) -> Unit,
    preview: suspend (Long) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    var scrubbed by remember(day) { mutableStateOf<Long?>(null) }
    val shown = scrubbed ?: time
    val latestTime by rememberUpdatedState(time)
    val seek by rememberUpdatedState(onSeek)
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val description = stringResource(R.string.timeline_description)
    val shownText = clockTime(shown)
    val hourFormat = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        scrubbed?.let { ScrubPreview(it, preview) }
        Text(shownText, style = MaterialTheme.typography.titleMediumEmphasized)
        Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .semantics {
                            contentDescription = description
                            stateDescription = shownText
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    (shown - day.start).toFloat(),
                                    0f..(day.end - day.start).toFloat(),
                                )
                            setProgress { value ->
                                seek(day.start + value.toLong())
                                true
                            }
                        }.pointerInput(day, zoom) {
                            val secondsPerPixel = zoom.spanSeconds.toFloat() / size.width
                            var exact = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    exact = (latestTime - day.start).toFloat()
                                    scrubbed = latestTime
                                },
                                onDragEnd = {
                                    scrubbed?.let { seek(it) }
                                    scrubbed = null
                                },
                                onDragCancel = { scrubbed = null },
                            ) { change, amount ->
                                change.consume()
                                // Dragging right brings earlier moments under the pointer.
                                exact =
                                    (exact - amount * secondsPerPixel)
                                        .coerceIn(0f, (day.end - day.start - 1).toFloat())
                                scrubbed = day.start + exact.toLong()
                            }
                        }.pointerInput(day, zoom) {
                            val secondsPerPixel = zoom.spanSeconds.toFloat() / size.width
                            detectTapGestures { offset ->
                                val away = (offset.x - size.width / 2f) * secondsPerPixel
                                seek(latestTime + away.toLong())
                            }
                        },
            ) {
                val pixelsPerSecond = size.width / zoom.spanSeconds
                val center = size.width / 2f

                fun x(moment: Long) = center + (moment - shown) * pixelsPerSecond

                val barTop = size.height * 0.45f
                val barHeight = size.height * 0.38f
                clipRect {
                    ranges.forEach { range ->
                        val left = x(range.start)
                        val right = x(range.end)
                        if (right >= 0f && left <= size.width) {
                            drawRect(colors.primaryContainer, Offset(left, barTop), Size(right - left, barHeight))
                        }
                    }
                    markers.forEach { marker ->
                        val left = x(marker.start)
                        val right = x(marker.end ?: marker.start)
                        if (right >= 0f && left <= size.width) {
                            drawRect(
                                if (marker.severity == ReviewSeverity.Alert) colors.error else colors.tertiary,
                                Offset(left, barTop),
                                Size(maxOf(right - left, MIN_MARK_WIDTH.toPx()), barHeight),
                            )
                        }
                    }
                    // Hour marks, aligned to the local clock.
                    val offset =
                        ZoneId
                            .systemDefault()
                            .rules
                            .getOffset(Instant.ofEpochSecond(shown))
                            .totalSeconds
                    val from = shown - zoom.spanSeconds / 2 - zoom.tickSeconds
                    var tick = from - (from + offset).mod(zoom.tickSeconds)
                    while (tick <= shown + zoom.spanSeconds / 2 + zoom.tickSeconds) {
                        val position = x(tick)
                        drawLine(
                            colors.outlineVariant,
                            Offset(position, barTop - 6.dp.toPx()),
                            Offset(position, barTop),
                            1.dp.toPx(),
                        )
                        val label = measurer.measure(hourFormat.format(Instant.ofEpochSecond(tick)), labelStyle)
                        drawText(label, topLeft = Offset(position - label.size.width / 2f, 6.dp.toPx()))
                        tick += zoom.tickSeconds
                    }
                }
                drawRoundRect(
                    colors.onSurface,
                    Offset(center - 1.5.dp.toPx(), barTop - 8.dp.toPx()),
                    Size(3.dp.toPx(), barHeight + 16.dp.toPx()),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

/** A picture of the moment under the finger, asked for once the finger rests briefly. */
@Composable
private fun ScrubPreview(
    time: Long,
    preview: suspend (Long) -> ByteArray?,
) {
    val picture by
        produceState<ImageBitmap?>(null, time) {
            delay(PREVIEW_PAUSE_MILLIS)
            val bytes = preview(time) ?: return@produceState
            value =
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
        }
    Surface(
        modifier = Modifier.width(160.dp).aspectRatio(16f / 9f),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(modifier = Modifier.padding(0.dp)) {
            picture?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
