package it.federicoraponi.frigatebridge.recordings

import it.federicoraponi.frigatebridge.camera.CameraRef

enum class ReviewSeverity {
    /** Something the user asked to be warned about. */
    Alert,
    Detection,
}

/** A moment Frigate marked for review. [end] is `null` while it is still going on. */
data class ReviewItem(
    val serverId: String,
    val id: String,
    val camera: CameraRef,
    val start: Long,
    val end: Long?,
    val severity: ReviewSeverity,
    /** What was seen, e.g. `person` or `car`. */
    val labels: List<String>,
    val zones: List<String>,
    /** Path of the small picture on the server. */
    val thumbnailPath: String,
    val isViewed: Boolean,
)
