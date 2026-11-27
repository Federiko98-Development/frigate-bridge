package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.Fixture
import it.federicoraponi.frigatebridge.server.NameProblem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CameraGroupEditorViewModelTest : ViewModelTest() {
    private val fixture = Fixture()
    private val home = fixture.home

    private suspend fun open(groupName: String?): CameraGroupEditorViewModel {
        home.config =
            """
            {
              "birdseye": {"enabled": true},
              "cameras": {"front_door": {}, "garden": {}, "garage": {}},
              "camera_groups": {
                "Outside": {"cameras": ["removed_camera", "birdseye", "garden"], "icon": "LuTrees", "order": 4},
                "Indoor": {"cameras": ["garage"], "order": 1}
              }
            }
            """.trimIndent()
        fixture.saveBoth()
        val viewModel =
            CameraGroupEditorViewModel(CameraGroupEditorArgs("home", groupName), fixture.registry, fixture.settings)
        viewModel.state.first { !it.isLoading }
        return viewModel
    }

    private suspend fun CameraGroupEditorViewModel.finished() = state.first { !it.isSaving }

    private suspend fun savedGroups() = checkNotNull(fixture.registry.session("home")).adminConfig().groups

    @Test
    fun putsANewGroupLast() =
        runTest {
            val viewModel = open(null)
            viewModel.setName("  Back yard ")
            viewModel.toggle("garden")

            viewModel.save()

            assertTrue(viewModel.finished().isDone)
            val group = savedGroups().single { it.name == "Back_yard" }
            assertEquals(5, group.order)
            assertEquals(listOf("garden"), group.cameras)
            val connection = checkNotNull(fixture.registry.session("home")).state.value
            val catalog = assertIs<ConnectionState.Connected>(connection).snapshot.catalog
            assertTrue(catalog.groups.any { it.name == "Back_yard" })
        }

    @Test
    fun keepsWhatItDoesNotChangeWhenRenaming() =
        runTest {
            val viewModel = open("Outside")
            fixture.settings.update { it.copy(hiddenCameras = mapOf("home/Outside" to setOf("garden"))) }
            assertEquals(setOf("birdseye", "garden"), viewModel.state.value.selected)
            viewModel.setName("Yard")

            viewModel.save()

            assertTrue(viewModel.finished().isDone)
            val groups = savedGroups()
            assertTrue(groups.none { it.name == "Outside" })
            val yard = groups.single { it.name == "Yard" }
            assertEquals(listOf("removed_camera", "birdseye", "garden"), yard.cameras)
            assertEquals("LuTrees", yard.icon)
            assertEquals(4, yard.order)
            val hidden =
                fixture.settings.settings
                    .first()
                    .hiddenCameras
            assertEquals(mapOf("home/Yard" to setOf("garden")), hidden)
        }

    @Test
    fun refusesATakenNameAndAGroupWithoutCameras() =
        runTest {
            val viewModel = open(null)
            viewModel.setName("Indoor")

            viewModel.save()

            assertEquals(NameProblem.Taken, viewModel.state.value.nameProblem)
            assertTrue(viewModel.state.value.camerasMissing)
            assertTrue(home.configSetBodies.isEmpty())
        }

    @Test
    fun deletesTheGroup() =
        runTest {
            val viewModel = open("Outside")
            fixture.settings.update { it.copy(hiddenCameras = mapOf("home/Outside" to setOf("garden"))) }

            viewModel.delete()

            assertTrue(viewModel.finished().isDone)
            assertEquals(listOf("Indoor"), savedGroups().map { it.name })
            assertTrue(
                fixture.settings.settings
                    .first()
                    .hiddenCameras
                    .isEmpty(),
            )
        }

    @Test
    fun saysWhenTheGroupWasNotSaved() =
        runTest {
            val viewModel = open("Outside")
            home.ignoreConfigWrites = true
            viewModel.setName("Yard")

            viewModel.save()

            val state = viewModel.finished()
            assertEquals(AdminFailure.NotSaved, state.failure)
            assertTrue(!state.isDone)
        }

    @Test
    fun leavesWhenThereIsNoSuchGroup() =
        runTest {
            assertTrue(open("Nowhere").state.value.isGone)
        }
}
