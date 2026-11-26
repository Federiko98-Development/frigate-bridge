package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.camera.CameraFeature

/** The switches of a camera. */
data class CameraControls(
    val enabled: Set<CameraFeature> = emptySet(),
    /** The functions that can be switched from the app. */
    val switchable: Set<CameraFeature> = emptySet(),
    /** Only administrators may change settings. */
    val isAllowed: Boolean = false,
    /** A change is on its way to the server. */
    val isApplying: Boolean = false,
    /** Why the last change failed. */
    val failure: VideoFailure? = null,
)

/** What a movable camera supports. */
data class PtzControls(
    val canPanAndTilt: Boolean,
    val canZoom: Boolean,
    val presets: List<String>,
)

enum class PtzMove(
    internal val command: String,
) {
    Up("MOVE_UP"),
    Down("MOVE_DOWN"),
    Left("MOVE_LEFT"),
    Right("MOVE_RIGHT"),
    ZoomIn("ZOOM_IN"),
    ZoomOut("ZOOM_OUT"),
}

internal fun Camera.controls(isAllowed: Boolean): CameraControls {
    val enabled =
        buildSet {
            if (isDetecting) add(CameraFeature.Detection)
            if (isRecording) add(CameraFeature.Recording)
            if (isSavingSnapshots) add(CameraFeature.Snapshots)
            if (isDetectingMotion) add(CameraFeature.Motion)
        }
    val switchable =
        CameraFeature.entries.filterTo(mutableSetOf()) {
            when (it) {
                CameraFeature.Recording -> canRecord
                // Detection needs motion detection: Frigate refuses to turn it off while detecting.
                CameraFeature.Motion -> !isDetecting
                else -> true
            }
        }
    return CameraControls(enabled, switchable, isAllowed)
}
