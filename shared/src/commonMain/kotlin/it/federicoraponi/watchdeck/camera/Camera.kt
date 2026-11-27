package it.federicoraponi.watchdeck.camera

import it.federicoraponi.watchdeck.frigate.CameraConfigDto
import it.federicoraponi.watchdeck.frigate.ConfigDto
import it.federicoraponi.watchdeck.frigate.UserProfileDto

/** A camera of a given server: two servers may use the same camera name. */
data class CameraRef(
    val serverId: String,
    val cameraName: String,
)

data class Camera(
    val ref: CameraRef,
    val displayName: String,
    val enabled: Boolean,
    /** Whether it appears in the "all cameras" view. Hidden cameras still appear in their groups. */
    val onDashboard: Boolean,
    /** The streams that can be watched live, the first being the preferred one. */
    val liveStreams: List<LiveStream> = emptyList(),
    val isRecording: Boolean = false,
    val isDetecting: Boolean = false,
    val isDetectingMotion: Boolean = false,
    val isSavingSnapshots: Boolean = false,
    /** Listening for sounds to detect. The app only switches it together with the rest, for the live view alone. */
    val isDetectingAudio: Boolean = false,
    /** `false` when recording is disabled in the configuration file and cannot be turned on from here. */
    val canRecord: Boolean = true,
    /** The camera may be movable; the server tells what it supports. */
    val hasPtz: Boolean = false,
)

/** A function of a camera that the app can turn on and off. [apiName] is Frigate's name for it. */
enum class CameraFeature(
    val apiName: String,
) {
    Detection("detect"),
    Recording("recordings"),
    Snapshots("snapshots"),
    Motion("motion"),
}

data class LiveStream(
    val label: String,
    /** The name of the stream in go2rtc. */
    val name: String,
)

data class CameraGroup(
    val name: String,
    val cameras: List<CameraRef>,
    /** The name of the icon chosen for the group, as the configuration holds it. */
    val icon: String = "generic",
)

data class CameraCatalog(
    val cameras: List<Camera> = emptyList(),
    val groups: List<CameraGroup> = emptyList(),
    /** Frigate lets only administrators change camera settings. */
    val canChangeSettings: Boolean = false,
) {
    /** The cameras of the group called [groupName], or those of the "all cameras" view when it is `null`. */
    fun camerasIn(groupName: String?): List<Camera> {
        val group = groups.firstOrNull { it.name == groupName } ?: return cameras.filter { it.onDashboard }
        val byRef = cameras.associateBy { it.ref }
        return group.cameras.mapNotNull { byRef[it] }
    }
}

/** Every camera of the configuration, in the order of the Frigate web interface. */
internal fun ConfigDto.camerasInOrder(): List<Pair<String, CameraConfigDto>> =
    cameras.entries
        .sortedWith(compareBy({ it.value.ui.order }, { it.key }))
        .map { it.key to it.value }

/** How the camera called [name] on the server is shown to the user. */
internal fun CameraConfigDto.displayName(name: String): String =
    friendlyName?.takeIf { it.isNotBlank() } ?: name.replace('_', ' ')

/** Builds the cameras that this user may see, in the order of the Frigate web interface. */
fun cameraCatalog(
    serverId: String,
    config: ConfigDto,
    profile: UserProfileDto,
): CameraCatalog {
    val allowed = profile.allowedCameras?.toSet()
    val cameras =
        config
            .camerasInOrder()
            .filter { (name, _) -> allowed == null || name in allowed }
            .map { (name, camera) ->
                Camera(
                    ref = CameraRef(serverId, name),
                    displayName = camera.displayName(name),
                    enabled = camera.enabled,
                    onDashboard = camera.ui.dashboard,
                    // Without a configured mapping the go2rtc stream carries the camera's name.
                    liveStreams =
                        camera.live.streams
                            .map { (label, stream) -> LiveStream(label, stream) }
                            .ifEmpty { listOf(LiveStream(name, name)) },
                    isRecording = camera.record.enabled,
                    isDetecting = camera.detect.enabled,
                    isDetectingMotion = camera.motion.enabled,
                    isSavingSnapshots = camera.snapshots.enabled,
                    isDetectingAudio = camera.audio.enabled,
                    canRecord = camera.record.enabledInConfig != false,
                    hasPtz = camera.onvif.host.isNotBlank(),
                )
            }
    val visible = cameras.map { it.ref.cameraName }.toSet()
    val groups =
        config.cameraGroups.entries
            .sortedWith(compareBy({ it.value.order }, { it.key }))
            .map { (name, group) ->
                CameraGroup(name, group.cameras.filter { it in visible }.map { CameraRef(serverId, it) }, group.icon)
            }.filter { it.cameras.isNotEmpty() }
    return CameraCatalog(cameras, groups, canChangeSettings = profile.role == "admin")
}
