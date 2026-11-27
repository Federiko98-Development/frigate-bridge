package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.recordings.ReviewItem
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// As Frigate's web interface shows a review moment.
private const val PADDING_SECONDS = 4L
private const val MAXIMUM_SECONDS = 3_600L

/** The first and last second of the video of [item]; a moment still going on ends now. */
@OptIn(ExperimentalTime::class)
internal fun clipRange(item: ReviewItem): LongRange {
    val start = item.start - PADDING_SECONDS
    val end = item.end?.let { it + PADDING_SECONDS } ?: Clock.System.now().epochSeconds
    // The server cannot put much more than an hour in one playlist.
    return start..end.coerceIn(start + 1, start + MAXIMUM_SECONDS)
}
