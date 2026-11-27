package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CameraGroupsViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    private suspend fun open(): CameraGroupsViewModel {
        fixture.saveBoth()
        fixture.registry.activate("home")
        val viewModel = CameraGroupsViewModel(fixture.registry, fixture.settings)
        viewModel.load()
        viewModel.state.first { !it.isLoading }
        return viewModel
    }

    private suspend fun CameraGroupsViewModel.finished() = state.first { !it.running }

    private fun catalogGroups(): List<String> =
        assertIs<ConnectionState.Connected>(checkNotNull(fixture.registry.session("home")).state.value)
            .snapshot
            .catalog
            .groups
            .map { it.name }

    @Test
    fun listsTheGroupsInOrder() =
        runTest {
            val state = open().state.value

            assertEquals("home", state.serverId)
            assertEquals(listOf("Indoor", "Outside"), state.groups.map { it.name })
            assertEquals(listOf("garage"), state.groups.first().cameraNames)
        }

    @Test
    fun reordersTheGroups() =
        runTest {
            val viewModel = open()

            viewModel.reorder(listOf("Outside", "Indoor"))
            // The list takes the new order before the server answers.
            assertEquals(
                listOf("Outside", "Indoor"),
                viewModel.state.value.groups
                    .map { it.name },
            )

            val state = viewModel.finished()
            assertEquals(null, state.actionFailure)
            assertEquals(listOf("Outside", "Indoor"), state.groups.map { it.name })
            assertEquals(listOf("Outside", "Indoor"), catalogGroups())
            assertEquals(
                """{"requires_restart":0,"config_data":{"camera_groups":{"Outside":{"order":1},""" +
                    """"Indoor":{"order":2}}}}""",
                fixture.home.configSetBodies.last(),
            )
        }

    @Test
    fun putsTheOrderBackWhenItWasNotSaved() =
        runTest {
            val viewModel = open()
            fixture.home.ignoreConfigWrites = true

            viewModel.reorder(listOf("Outside", "Indoor"))

            val state = viewModel.finished()
            assertEquals(AdminFailure.NotSaved, state.actionFailure)
            assertEquals(listOf("Indoor", "Outside"), state.groups.map { it.name })
        }

    @Test
    fun deletesAGroupAndItsHiddenCameras() =
        runTest {
            val viewModel = open()
            fixture.settings.update {
                it.copy(hiddenCameras = mapOf("home/Outside" to setOf("garden"), "home/Indoor" to setOf("garage")))
            }

            viewModel.delete("Outside")

            assertEquals(listOf("Indoor"), viewModel.finished().groups.map { it.name })
            assertEquals(listOf("Indoor"), catalogGroups())
            assertEquals(setOf("home/Indoor"), hidden().keys)
        }

    @Test
    fun takesAGroupThatIsAlreadyGoneAsDeleted() =
        runTest {
            val viewModel = open()
            fixture.home.config = """{"cameras": {"garage": {}}, "camera_groups": {}}"""

            viewModel.delete("Outside")

            val state = viewModel.finished()
            assertEquals(null, state.actionFailure)
            assertEquals(emptyList(), state.groups)
        }

    @Test
    fun saysWhenAChangeWasNotSaved() =
        runTest {
            val viewModel = open()
            fixture.home.ignoreConfigWrites = true

            viewModel.delete("Outside")

            assertEquals(AdminFailure.NotSaved, viewModel.finished().actionFailure)
        }

    private suspend fun hidden(): Map<String, Set<String>> =
        fixture.settings.settings
            .first()
            .hiddenCameras
}
