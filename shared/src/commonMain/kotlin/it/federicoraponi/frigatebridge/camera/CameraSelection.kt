package it.federicoraponi.frigatebridge.camera

import it.federicoraponi.frigatebridge.settings.AppSettings

/** A camera, and whether the user left it switched on. */
data class CameraChoice(
    val camera: Camera,
    val isShown: Boolean,
)

/** All the cameras ([name] `null`) or one group of them, some of which the user may have switched off. */
data class GroupChoice(
    val name: String?,
    val cameras: List<CameraChoice>,
) {
    val shown: List<Camera> get() = cameras.filter { it.isShown }.map { it.camera }

    val isPartial: Boolean get() = cameras.any { !it.isShown }
}

/** Under which the cameras switched off in a group of a server are saved. */
private fun groupKey(
    serverId: String,
    group: String?,
) = "$serverId/${group.orEmpty()}"

/**
 * "All cameras", holding [everyCamera], followed by the server's groups, with the cameras [hidden] in each as
 * [AppSettings.hiddenCameras] saves them.
 */
fun CameraCatalog.groupChoices(
    serverId: String,
    everyCamera: List<Camera>,
    hidden: Map<String, Set<String>>,
): List<GroupChoice> {
    fun choice(
        name: String?,
        cameras: List<Camera>,
    ): GroupChoice {
        val off = hidden[groupKey(serverId, name)].orEmpty()
        return GroupChoice(name, cameras.map { CameraChoice(it, it.ref.cameraName !in off) })
    }
    return listOf(choice(null, everyCamera)) + groups.map { choice(it.name, camerasIn(it.name)) }
}

/**
 * The settings with only the cameras named in [shown] switched on in [choice]. Switching all of them off is not
 * possible: the settings stay as they are.
 */
fun AppSettings.showing(
    serverId: String,
    choice: GroupChoice,
    shown: Set<String>,
): AppSettings {
    val names = choice.cameras.map { it.camera.ref.cameraName }
    if (names.none { it in shown }) return this
    val key = groupKey(serverId, choice.name)
    val off = names.filterNot { it in shown }.toSet()
    return copy(hiddenCameras = if (off.isEmpty()) hiddenCameras - key else hiddenCameras + (key to off))
}
