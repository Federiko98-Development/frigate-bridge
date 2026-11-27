package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.camera.CameraFeature

/** The switches of a camera. */
data class CameraControls(
    val enabled: Set<CameraFeature> = emptySet(),
    /** The functions this camera offers: the others are not shown. */
    val available: Set<CameraFeature> = emptySet(),
    /** Those that can be switched right now. */
    val switchable: Set<CameraFeature> = emptySet(),
    /** Only administrators may change settings. */
    val isAllowed: Boolean = false,
    /** A change is on its way to the server. */
    val isApplying: Boolean = false,
    /** Nothing is recorded or detected: the camera only shows its live view. */
    val isLiveOnly: Boolean = false,
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
    // Recording cannot be offered at all when the configuration file disables it.
    val available = CameraFeature.entries.filterTo(mutableSetOf()) { it != CameraFeature.Recording || canRecord }
    // Detection needs motion detection: Frigate refuses to turn it off while detecting.
    val switchable = available.filterTo(mutableSetOf()) { it != CameraFeature.Motion || !isDetecting }
    return CameraControls(
        enabled = enabled,
        available = available,
        switchable = switchable,
        isAllowed = isAllowed,
        isLiveOnly = enabled.isEmpty() && !isDetectingAudio,
    )
}
