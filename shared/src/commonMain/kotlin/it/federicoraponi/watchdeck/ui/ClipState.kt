package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.server.ExportedFile

/** A clip being recorded from the live view. */
sealed interface ClipState {
    data object None : ClipState

    /** The server is being asked to start. */
    data object Starting : ClipState

    data class Recording(
        /** Seconds since the epoch, by the phone's clock. */
        val startedAt: Long,
    ) : ClipState

    /** Stopped: the server is putting the file together. */
    data object Preparing : ClipState

    /** Being saved on the phone. */
    data object Saving : ClipState

    /** To be saved on the phone. */
    data class Ready(
        val file: ExportedFile,
        /** When the recording started, in seconds since the epoch. */
        val startedAt: Long,
    ) : ClipState

    data class Failed(
        val failure: VideoFailure,
    ) : ClipState
}

/** After this long a recording stops by itself. */
const val MAXIMUM_CLIP_MILLIS = 300_000L

// The last pieces of video reach the server's database a little after the recording ends.
internal const val CLIP_SETTLE_MILLIS = 12_000L
