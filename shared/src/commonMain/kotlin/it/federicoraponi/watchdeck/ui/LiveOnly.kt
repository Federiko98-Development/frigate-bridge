package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.camera.Camera
import it.federicoraponi.watchdeck.camera.CameraFeature
import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.ServerSession
import it.federicoraponi.watchdeck.settings.SettingsStore
import kotlinx.coroutines.flow.first

// Frigate's names of the functions that "live view only" switches.
private val DETECTION = CameraFeature.Detection.apiName
private val RECORDING = CameraFeature.Recording.apiName
private val SNAPSHOTS = CameraFeature.Snapshots.apiName
private val MOTION = CameraFeature.Motion.apiName
private const val AUDIO = "audio"

/** The camera as the server last described it, or `null`. */
internal fun ServerSession.camera(ref: CameraRef): Camera? =
    (state.value as? ConnectionState.Connected)
        ?.snapshot
        ?.catalog
        ?.cameras
        ?.firstOrNull { it.ref == ref }

/**
 * Leaves a camera with its live view alone ([on]): nothing is recorded and nothing detected. Switched back off, what
 * was on before comes back. Like every switch, it lasts until Frigate restarts. Throws what the server answers.
 */
internal suspend fun ServerSession.switchLiveOnly(
    ref: CameraRef,
    on: Boolean,
    settings: SettingsStore,
) {
    val current = camera(ref) ?: return
    val key = "${ref.serverId}/${ref.cameraName}"
    if (on) {
        // Detection goes before motion: Frigate keeps motion on while it detects.
        val running =
            listOfNotNull(
                RECORDING.takeIf { current.isRecording },
                SNAPSHOTS.takeIf { current.isSavingSnapshots },
                DETECTION.takeIf { current.isDetecting },
                MOTION.takeIf { current.isDetectingMotion },
                AUDIO.takeIf { current.isDetectingAudio },
            )
        // Saved first: a failure half way must not lose what to switch back on.
        if (running.isNotEmpty()) {
            settings.update { it.copy(liveOnlyRestore = it.liveOnlyRestore + (key to running.toSet())) }
        }
        setCameraFeatures(ref.cameraName, running.map { it to false })
    } else {
        // Without a note of what was on, e.g. switched off elsewhere, the usual functions come back.
        val before = settings.settings.first().liveOnlyRestore[key] ?: setOf(MOTION, DETECTION, RECORDING)
        val wanted =
            listOf(MOTION, DETECTION, RECORDING, SNAPSHOTS, AUDIO)
                .filter { it in before && (it != RECORDING || current.canRecord) }
        setCameraFeatures(ref.cameraName, wanted.map { it to true })
        settings.update { it.copy(liveOnlyRestore = it.liveOnlyRestore - key) }
    }
}
