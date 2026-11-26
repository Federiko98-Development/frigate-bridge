package it.federicoraponi.frigatebridge.recordings

import it.federicoraponi.frigatebridge.camera.CameraRef

// Marks, in the name of an export, that it keeps a review moment. The web interface shows it too.
private const val SAVED_MARK = " #saved-"

// The server refuses longer names.
private const val MAXIMUM_TITLE = 180

/** A video file kept on the server, until someone deletes it. */
data class ExportItem(
    val serverId: String,
    val id: String,
    val camera: CameraRef,
    val name: String,
    /** When it was made, in seconds since the epoch. */
    val date: Long,
    /** Path of the video on the server. */
    val videoPath: String,
    /** Path of its small picture on the server, empty if it has none. */
    val thumbnailPath: String,
    val inProgress: Boolean,
) {
    /** The review moment this export was made to keep, or `null` for any other export. */
    val savedReviewId: String? get() = name.substringAfterLast(SAVED_MARK, "").takeIf { it.isNotBlank() }

    val title: String get() = if (savedReviewId == null) name else name.substringBeforeLast(SAVED_MARK)
}

/** The name of an export that keeps the review moment [reviewId]. */
fun savedExportName(
    title: String,
    reviewId: String,
): String = "${title.take(MAXIMUM_TITLE)}$SAVED_MARK$reviewId"
