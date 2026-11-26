package it.federicoraponi.frigatebridge.recordings

/** A recorded file: [duration] is the length of its video, which may differ a little from `end - start`. */
data class RecordingSegment(
    val start: Double,
    val end: Double,
    val duration: Double = end - start,
)

/** A stretch of time with recordings, in seconds since the epoch. */
data class TimeRange(
    val start: Long,
    val end: Long,
)

// Consecutive files are a fraction of a second apart.
private const val GAP_TOLERANCE_SECONDS = 2.0

/** The stretches covered by [segments], joining the ones that follow each other. */
fun recordedRanges(segments: List<RecordingSegment>): List<TimeRange> {
    val ranges = mutableListOf<TimeRange>()
    var start = 0.0
    var end = 0.0
    for (segment in segments.sortedBy { it.start }) {
        if (end > 0.0 && segment.start <= end + GAP_TOLERANCE_SECONDS) {
            end = maxOf(end, segment.end)
        } else {
            if (end > 0.0) ranges += TimeRange(start.toLong(), end.toLong())
            start = segment.start
            end = segment.end
        }
    }
    if (end > 0.0) ranges += TimeRange(start.toLong(), end.toLong())
    return ranges
}

private const val MILLIS = 1_000.0

/**
 * Translates between the position of a player and the time of day, for the video of the interval [start]–[end].
 *
 * Frigate joins the recorded files one after the other and leaves out the pauses between them, so the position
 * in the video is not simply the time passed since [start].
 */
class PlaybackWindow(
    val start: Long,
    val end: Long,
    segments: List<RecordingSegment>,
) {
    private data class Piece(
        val startTime: Double,
        val length: Double,
        val offset: Double,
    )

    private val pieces: List<Piece> =
        buildList {
            var offset = 0.0
            segments
                .filter { it.end > start && it.start < end }
                .sortedBy { it.start }
                .forEach { segment ->
                    // The first and the last file are cut at the edges of the interval.
                    val skipped = maxOf(0.0, start - segment.start)
                    val cut = maxOf(0.0, segment.end - end)
                    val length = segment.duration - skipped - cut
                    if (length > 0) {
                        add(Piece(segment.start + skipped, length, offset))
                        offset += length
                    }
                }
        }

    val isEmpty: Boolean get() = pieces.isEmpty()

    /** Whether [time] falls inside the interval this video was made for. */
    fun covers(time: Long): Boolean = time in start until end

    /** The position showing [time]; during a pause, the next recorded moment. */
    fun positionMillisAt(time: Long): Long {
        val piece = pieces.firstOrNull { time < it.startTime + it.length } ?: pieces.lastOrNull() ?: return 0
        val inside = (time - piece.startTime).coerceIn(0.0, piece.length)
        return ((piece.offset + inside) * MILLIS).toLong()
    }

    /** The time of day shown at [positionMillis]. */
    fun timeAt(positionMillis: Long): Long {
        val position = positionMillis / MILLIS
        val piece = pieces.lastOrNull { it.offset <= position } ?: pieces.firstOrNull() ?: return start
        return (piece.startTime + (position - piece.offset).coerceIn(0.0, piece.length)).toLong()
    }
}
