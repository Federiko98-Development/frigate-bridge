package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ExportedFile

sealed interface ExportState {
    data object None : ExportState

    /** The server is cutting the file. */
    data object Working : ExportState

    data class Ready(
        val file: ExportedFile,
    ) : ExportState

    data class Failed(
        val failure: VideoFailure,
    ) : ExportState
}

/**
 * What went wrong with a video, an export or a command. An unexpected answer carries no [ConnectionProblem]: the
 * server is already known to be Frigate, so the screen says what failed and shows the detail.
 */
internal fun FrigateError.toVideoFailure() =
    VideoFailure(
        problem = toProblem().takeUnless { it == ConnectionProblem.UnexpectedResponse },
        detail = (this as? FrigateError.UnexpectedResponse)?.detail,
    )
