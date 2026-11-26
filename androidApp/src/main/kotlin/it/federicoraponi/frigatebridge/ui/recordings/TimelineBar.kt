package it.federicoraponi.frigatebridge.ui.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import it.federicoraponi.frigatebridge.recordings.snapPoint
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

// How long the strip must be left alone before the video moves, so that several swipes make one journey.
private const val SETTLE_MILLIS = 700L

// How long the chosen moment stays under the pointer while the video catches up.
private const val FOLLOW_MILLIS = 1_500L

// How close the cursor must be, on screen, to the start of a clip to hold on to it.
private val SNAP_REACH = 12.dp

// A moment shorter than this would be too thin to see.
private val MIN_MARK_WIDTH = 3.dp

// The strip at the normal font size. The hour labels above the bar grow with the font, and the strip with them.
private val STRIP_HEIGHT = 64.dp
private val LABEL_TOP = 6.dp
private val BAR_TOP = STRIP_HEIGHT * 0.45f
private val BAR_HEIGHT = STRIP_HEIGHT * 0.38f
private val BAR_BOTTOM_SPACE = STRIP_HEIGHT - BAR_TOP - BAR_HEIGHT

// Alerts rise above the bar, so that they differ from detections by more than their colour.
private val ALERT_RISE = 4.dp

// How far the actions offered to screen readers move, in seconds.
private const val ACCESSIBLE_STEP_SECONDS = 60

/**
 * The day as a strip that slides under a fixed pointer: recorded stretches, with the review moments on top.
 * It can be dragged and flung, as many times as needed to travel far; the video moves to the moment under the
 * pointer once the strip is left alone. A tap brings a point under the pointer. Near the start of a recording or
 * of a moment the pointer holds on to it, with a tick of the phone's vibration.
 */
@Composable
fun TimelineBar(
    day: RecordingDay,
    ranges: List<TimeRange>,
    markers: List<ReviewItem>,
    time: Long,
    zoom: TimelineZoom,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The moment under the pointer while the user is looking for one; the video's time is ignored meanwhile.
    var browsed by remember(day) { mutableStateOf<Double?>(null) }
    var width by remember { mutableIntStateOf(1) }
    val points = remember(ranges, markers) { (ranges.map { it.start } + markers.map { it.start }).distinct() }
    val reachPixels = with(LocalDensity.current) { SNAP_REACH.toPx() }
    val held = browsed?.let { snapPoint(points, it, reachPixels * zoom.spanSeconds.toDouble() / width) }
    val shown = held ?: browsed?.toLong() ?: time
    val latestHeld by rememberUpdatedState(held)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(held) {
        if (held != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val latestTime by rememberUpdatedState(time)
    val seek by rememberUpdatedState(onSeek)

    fun browseTo(moment: Double): Double =
        moment.coerceIn(day.start.toDouble(), (day.end - 1).toDouble()).also { browsed = it }

    val scroll =
        rememberScrollableState { pixels ->
            val secondsPerPixel = zoom.spanSeconds.toDouble() / width
            val from = browsed ?: latestTime.toDouble()
            // Dragging right brings earlier moments under the pointer. What is left at midnight is not used.
            val to = browseTo(from - pixels * secondsPerPixel)
            ((from - to) / secondsPerPixel).toFloat()
        }
    LaunchedEffect(day, scroll) {
        snapshotFlow { scroll.isScrollInProgress to browsed }.collectLatest { (isMoving, chosen) ->
            if (isMoving || chosen == null) return@collectLatest
            delay(SETTLE_MILLIS)
            val before = latestTime
            seek(latestHeld ?: chosen.toLong())
            withTimeoutOrNull(FOLLOW_MILLIS) { snapshotFlow { latestTime }.first { it != before } }
            browsed = null
        }
    }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val density = LocalDensity.current
    val labelPixels = remember(measurer, labelStyle, density) { measurer.measure("00:00", labelStyle).size.height }
    val labelHeight = with(density) { labelPixels.toDp() }
    val barOffset = maxOf(BAR_TOP, LABEL_TOP + labelHeight + LABEL_TOP)
    val description = stringResource(R.string.timeline_description)
    val shownText = clockTime(shown)
    val hourFormat = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    // What the colours of the strip say, for those who cannot see them.
    val markerHere = markers.firstOrNull { shown >= it.start && shown <= (it.end ?: it.start) }
    val recordedHere = ranges.any { shown >= it.start && shown < it.end }
    val state =
        listOfNotNull(
            shownText,
            markerHere?.let {
                stringResource(
                    if (it.severity == ReviewSeverity.Alert) R.string.event_alert else R.string.event_detection,
                )
            },
            stringResource(R.string.timeline_not_recorded).takeIf { !recordedHere },
        ).joinToString(", ")
    val marks = remember(points) { points.sorted() }
    val actions =
        listOf(
            stringResource(R.string.skip_back_60) to { shown - ACCESSIBLE_STEP_SECONDS },
            stringResource(R.string.skip_forward_60) to { shown + ACCESSIBLE_STEP_SECONDS },
            stringResource(R.string.timeline_previous_mark) to { marks.lastOrNull { it < shown - 1 } },
            stringResource(R.string.timeline_next_mark) to { marks.firstOrNull { it > shown + 1 } },
        ).map { (label, target) ->
            CustomAccessibilityAction(label) {
                val moment = target() ?: return@CustomAccessibilityAction false
                seek(moment.coerceIn(day.start, day.end - 1))
                true
            }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // The strip below says it too, with what is happening at that moment.
        Text(
            shownText,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = colors.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(maxOf(STRIP_HEIGHT, barOffset + BAR_HEIGHT + BAR_BOTTOM_SPACE))
                        .onSizeChanged { width = maxOf(it.width, 1) }
                        .semantics {
                            contentDescription = description
                            stateDescription = state
                            customActions = actions
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    (shown - day.start).toFloat(),
                                    0f..(day.end - day.start).toFloat(),
                                )
                            setProgress { value ->
                                seek(day.start + value.toLong())
                                true
                            }
                        }.scrollable(scroll, Orientation.Horizontal)
                        .pointerInput(day, zoom) {
                            val secondsPerPixel = zoom.spanSeconds.toDouble() / size.width
                            detectTapGestures { offset ->
                                val away = (offset.x - size.width / 2f) * secondsPerPixel
                                browseTo((browsed ?: latestTime.toDouble()) + away)
                            }
                        },
            ) {
                val pixelsPerSecond = size.width / zoom.spanSeconds
                val center = size.width / 2f

                fun x(moment: Long) = center + (moment - shown) * pixelsPerSecond

                val barTop = barOffset.toPx()
                val barHeight = BAR_HEIGHT.toPx()
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
                            val alert = marker.severity == ReviewSeverity.Alert
                            val rise = if (alert) ALERT_RISE.toPx() else 0f
                            drawRect(
                                if (alert) colors.error else colors.tertiary,
                                Offset(left, barTop - rise),
                                Size(maxOf(right - left, MIN_MARK_WIDTH.toPx()), barHeight + rise),
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
                        drawText(label, topLeft = Offset(position - label.size.width / 2f, LABEL_TOP.toPx()))
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
