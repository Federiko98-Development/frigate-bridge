package it.federicoraponi.frigatebridge.camera

import it.federicoraponi.frigatebridge.frigate.ConfigDto
import it.federicoraponi.frigatebridge.frigate.UserProfileDto

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
)

data class LiveStream(
    val label: String,
    /** The name of the stream in go2rtc. */
    val name: String,
)

data class CameraGroup(
    val name: String,
    val cameras: List<CameraRef>,
)

data class CameraCatalog(
    val cameras: List<Camera> = emptyList(),
    val groups: List<CameraGroup> = emptyList(),
) {
    /** The cameras of the group called [groupName], or those of the "all cameras" view when it is `null`. */
    fun camerasIn(groupName: String?): List<Camera> {
        val group = groups.firstOrNull { it.name == groupName } ?: return cameras.filter { it.onDashboard }
        val byRef = cameras.associateBy { it.ref }
        return group.cameras.mapNotNull { byRef[it] }
    }
}

/** Builds the cameras that this user may see, in the order of the Frigate web interface. */
fun cameraCatalog(
    serverId: String,
    config: ConfigDto,
    profile: UserProfileDto,
): CameraCatalog {
    val allowed = profile.allowedCameras?.toSet()
    val cameras =
        config.cameras.entries
            .filter { (name, _) -> allowed == null || name in allowed }
            .sortedWith(compareBy({ it.value.ui.order }, { it.key }))
            .map { (name, camera) ->
                Camera(
                    ref = CameraRef(serverId, name),
                    displayName = camera.friendlyName?.takeIf { it.isNotBlank() } ?: name.replace('_', ' '),
                    enabled = camera.enabled,
                    onDashboard = camera.ui.dashboard,
                    // Without a configured mapping the go2rtc stream carries the camera's name.
                    liveStreams =
                        camera.live.streams
                            .map { (label, stream) -> LiveStream(label, stream) }
                            .ifEmpty { listOf(LiveStream(name, name)) },
                )
            }
    val visible = cameras.map { it.ref.cameraName }.toSet()
    val groups =
        config.cameraGroups.entries
            .sortedWith(compareBy({ it.value.order }, { it.key }))
            .map { (name, group) ->
                CameraGroup(name, group.cameras.filter { it in visible }.map { CameraRef(serverId, it) })
            }.filter { it.cameras.isNotEmpty() }
    return CameraCatalog(cameras, groups)
}
