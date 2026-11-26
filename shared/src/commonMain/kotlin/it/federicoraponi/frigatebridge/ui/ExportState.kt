package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.delay

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

/** Has the server cut a file of an interval and waits until it can be downloaded. */
internal suspend fun ServerSession.cutExport(
    camera: String,
    start: Long,
    end: Long,
): ExportState =
    try {
        val id = startExport(camera, start, end)
        var file: ExportedFile? = null
        var checks = 0
        while (file == null && checks++ < EXPORT_CHECKS) {
            delay(EXPORT_CHECK_MILLIS)
            file = exportedFile(id)
        }
        file?.let { ExportState.Ready(it) }
            ?: ExportState.Failed(VideoFailure(detail = "The server is still working on the export"))
    } catch (error: FrigateError) {
        ExportState.Failed(error.toVideoFailure())
    }

private const val EXPORT_CHECK_MILLIS = 2_000L
private const val EXPORT_CHECKS = 90

/**
 * What went wrong with a video, an export or a command. An unexpected answer carries no [ConnectionProblem]: the
 * server is already known to be Frigate, so the screen says what failed and shows the detail.
 */
internal fun FrigateError.toVideoFailure() =
    VideoFailure(
        problem = toProblem().takeUnless { it == ConnectionProblem.UnexpectedResponse },
        detail = (this as? FrigateError.UnexpectedResponse)?.detail,
    )
