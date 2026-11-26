package it.federicoraponi.frigatebridge.camera

import it.federicoraponi.frigatebridge.frigate.ConfigDto
import it.federicoraponi.frigatebridge.frigate.FakeFrigate
import it.federicoraponi.frigatebridge.frigate.UserProfileDto
import it.federicoraponi.frigatebridge.frigate.frigateJson
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraCatalogTest {
    private val config = frigateJson.decodeFromString<ConfigDto>(FakeFrigate.SAMPLE_CONFIG)

    private fun catalog(allowed: List<String>? = null) =
        cameraCatalog("home", config, UserProfileDto(allowedCameras = allowed))

    private fun List<Camera>.names() = map { it.ref.cameraName }

    @Test
    fun sortsByUiOrderThenName() {
        assertEquals(listOf("front_door", "garden", "garage"), catalog().cameras.names())
    }

    @Test
    fun showsTheFriendlyNameOrAReadableCameraName() {
        assertEquals(listOf("Front door", "garden", "garage"), catalog().cameras.map { it.displayName })
    }

    @Test
    fun leavesHiddenCamerasOutOfAllCamerasButNotOutOfTheirGroups() {
        val catalog = catalog()

        assertEquals(listOf("front_door", "garage"), catalog.camerasIn(null).names())
        assertEquals(listOf("garden", "front_door"), catalog.camerasIn("Outside").names())
    }

    @Test
    fun sortsGroupsByTheirOrder() {
        assertEquals(listOf("Indoor", "Outside"), catalog().groups.map { it.name })
    }

    @Test
    fun showsOnlyTheCamerasAllowedToTheUser() {
        val catalog = catalog(allowed = listOf("front_door"))

        assertEquals(listOf("front_door"), catalog.cameras.names())
        assertEquals(listOf("Outside"), catalog.groups.map { it.name })
    }
}
