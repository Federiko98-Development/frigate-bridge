package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.recordings.PlaybackWindow
import it.federicoraponi.frigatebridge.recordings.RecordingDay
import it.federicoraponi.frigatebridge.recordings.RecordingSegment
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.TimeRange
import it.federicoraponi.frigatebridge.recordings.recordedRanges
import it.federicoraponi.frigatebridge.server.RecordingClip
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** Which camera, and the moment to start from; without one, the latest recording. */
data class TimelineArgs(
    val camera: CameraRef,
    val startTime: Long? = null,
    /** The time zone of the phone, used to tell the days apart. */
    val zoneId: String = TimeZone.currentSystemDefault().id,
)

/** A request to move the player, told apart from the previous one by [id]. */
data class SeekRequest(
    val positionMillis: Long,
    val id: Int,
)

sealed interface PlaybackState {
    data object Loading : PlaybackState

    data class Ready(
        val clip: RecordingClip,
        val seek: SeekRequest,
    ) : PlaybackState

    /** Nothing was recorded around the chosen moment. */
    data object Empty : PlaybackState

    data class Failed(
        val failure: VideoFailure,
    ) : PlaybackState
}

data class TimelineState(
    /** Newest first. */
    val days: List<RecordingDay> = emptyList(),
    val day: RecordingDay? = null,
    /** The recorded stretches of the chosen day. */
    val ranges: List<TimeRange> = emptyList(),
    val markers: List<ReviewItem> = emptyList(),
    /** The moment on screen, in seconds since the epoch. */
    val time: Long = 0,
    val playback: PlaybackState = PlaybackState.Loading,
    val export: ExportState = ExportState.None,
    val isGone: Boolean = false,
)

/** The recordings of one camera, day by day. */
class TimelineViewModel(
    private val args: TimelineArgs,
    private val registry: ServerRegistry,
) : ViewModel() {
    private val camera = args.camera
    private var segments: List<RecordingSegment> = emptyList()
    private var window: PlaybackWindow? = null
    private var dayJob: Job? = null
    private var clipJob: Job? = null
    private var exportJob: Job? = null
    private var seeks = 0
    private val mutableState = MutableStateFlow(TimelineState())
    val state: StateFlow<TimelineState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        val current = mutableState.value
        when {
            current.day == null -> load()
            segments.isEmpty() -> openDay(current.day, current.time)
            else -> playFrom(current.time, forceReload = true)
        }
    }

    fun selectDay(date: String) {
        val day = mutableState.value.days.firstOrNull { it.date == date } ?: return
        if (day != mutableState.value.day) openDay(day, startTime = null)
    }

    /** Moves to [time], loading another stretch of video if needed. */
    fun seekTo(time: Long) {
        val day = mutableState.value.day ?: return
        playFrom(time.coerceIn(day.start, day.end - 1))
    }

    fun skip(seconds: Int) = seekTo(mutableState.value.time + seconds)

    /** The player moved on. */
    fun playerAt(positionMillis: Long) {
        val current = window ?: return
        if (mutableState.value.playback !is PlaybackState.Ready) return
        mutableState.update { it.copy(time = current.timeAt(positionMillis)) }
    }

    /** The video of this stretch is over: continue with what was recorded afterwards. */
    fun playerEnded() {
        val ended = window ?: return
        val next = segments.firstOrNull { it.start >= ended.end }
        if (next != null) playFrom(next.start.toLong())
    }

    fun playerFailed(detail: String?) {
        mutableState.update { it.copy(playback = PlaybackState.Failed(VideoFailure(detail = detail))) }
    }

    /** A small picture of the moment [time], to show while looking for a point on the timeline. */
    suspend fun preview(time: Long): ByteArray? =
        try {
            registry.session(camera.serverId)?.recordedFrame(camera.cameraName, time, PREVIEW_FRAME_HEIGHT)
        } catch (_: FrigateError) {
            null
        }

    /** Asks the server for a file with the [seconds] that follow [start]. */
    fun export(
        start: Long,
        seconds: Int,
    ) {
        if (mutableState.value.export is ExportState.Working) return
        exportJob?.cancel()
        mutableState.update { it.copy(export = ExportState.Working) }
        exportJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                val result = session.cutExport(camera.cameraName, start, start + seconds)
                mutableState.update { it.copy(export = result) }
            }
    }

    /** The exported file was handed over, or the message about it dismissed. */
    fun clearExport() {
        if (mutableState.value.export !is ExportState.Working) {
            mutableState.update { it.copy(export = ExportState.None) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            registry.load()
            val session = session() ?: return@launch
            mutableState.update { it.copy(playback = PlaybackState.Loading) }
            try {
                val days = session.recordingDays(camera.cameraName, args.zoneId)
                val wanted = args.startTime
                val day =
                    days.firstOrNull { wanted != null && wanted >= it.start && wanted < it.end } ?: days.firstOrNull()
                mutableState.update { it.copy(days = days) }
                if (day == null) {
                    mutableState.update { it.copy(playback = PlaybackState.Empty) }
                } else {
                    openDay(day, wanted)
                }
            } catch (error: FrigateError) {
                fail(error)
            }
        }
    }

    private fun openDay(
        day: RecordingDay,
        startTime: Long?,
    ) {
        dayJob?.cancel()
        clipJob?.cancel()
        segments = emptyList()
        window = null
        mutableState.update {
            it.copy(
                day = day,
                ranges = emptyList(),
                markers = emptyList(),
                time = startTime ?: day.start,
                playback = PlaybackState.Loading,
            )
        }
        dayJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                try {
                    segments = session.recordingSegments(camera.cameraName, day.start, day.end)
                    val ranges = recordedRanges(segments)
                    mutableState.update { it.copy(ranges = ranges) }
                    val latest = ranges.lastOrNull()
                    if (latest == null) {
                        mutableState.update { it.copy(playback = PlaybackState.Empty) }
                    } else {
                        // Without a chosen moment, the last minute recorded that day.
                        playFrom(startTime ?: maxOf(latest.start, latest.end - LATEST_LEAD_SECONDS))
                    }
                } catch (error: FrigateError) {
                    fail(error)
                    return@launch
                }
                // The marks are an extra: the video plays without them.
                try {
                    val markers = session.reviews(day.start, day.end, cameras = listOf(camera.cameraName))
                    mutableState.update { it.copy(markers = markers) }
                } catch (_: FrigateError) {
                }
            }
    }

    private fun playFrom(
        time: Long,
        forceReload: Boolean = false,
    ) {
        // A moment without video gives way to the next recorded one, or to the end of the last.
        val covering = segments.firstOrNull { it.end > time } ?: segments.lastOrNull() ?: return
        val target = maxOf(time, covering.start.toLong()).coerceAtMost(covering.end.toLong() - 1)
        val current = window
        val playback = mutableState.value.playback
        if (!forceReload && current != null && current.covers(target) && playback is PlaybackState.Ready) {
            val seek = SeekRequest(current.positionMillisAt(target), ++seeks)
            mutableState.update { it.copy(time = target, playback = playback.copy(seek = seek)) }
            return
        }
        // One hour at a time: a whole day is more than the server can put in one playlist.
        val start = target - target.mod(WINDOW_SECONDS)
        val next = PlaybackWindow(start, start + WINDOW_SECONDS, segments)
        window = next
        clipJob?.cancel()
        mutableState.update { it.copy(time = target, playback = PlaybackState.Loading) }
        clipJob =
            viewModelScope.launch {
                try {
                    val session = session() ?: return@launch
                    val clip = session.recordingClip(camera.cameraName, next.start, next.end)
                    val seek = SeekRequest(next.positionMillisAt(target), ++seeks)
                    mutableState.update { it.copy(playback = PlaybackState.Ready(clip, seek)) }
                } catch (error: FrigateError) {
                    fail(error)
                }
            }
    }

    private fun fail(error: FrigateError) {
        mutableState.update { it.copy(playback = PlaybackState.Failed(error.toVideoFailure())) }
    }

    private fun session(): ServerSession? {
        val session = registry.session(camera.serverId)
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }
}

private const val WINDOW_SECONDS = 3_600L
private const val LATEST_LEAD_SECONDS = 60L
private const val PREVIEW_FRAME_HEIGHT = 180
