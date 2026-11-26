package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.network.NetworkStatus
import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CamerasViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    private suspend fun CamerasViewModel.cameras(serverId: String): CamerasState =
        state.first { it.server?.id == serverId && it.content is CamerasContent.Ready }

    private fun CamerasState.names() = (content as CamerasContent.Ready).cameras.map { it.ref.cameraName }

    @Test
    fun switchingServerShowsOnlyTheNewServersCameras() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            val viewModel = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            assertEquals(listOf("front_door", "garage"), viewModel.cameras("home").names())

            viewModel.switchTo("office")

            val office = viewModel.cameras("office")
            assertEquals(listOf("front_door", "warehouse"), office.names())
            assertEquals(emptyList(), office.groups)
            assertEquals(listOf("Home", "Office"), office.servers.map { it.name })
        }

    @Test
    fun filtersByGroupAndForgetsTheSelectionOnAnotherServer() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            val viewModel = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            viewModel.cameras("home")

            viewModel.selectGroup("Outside")
            val outside = viewModel.state.first { it.selectedGroup == "Outside" }
            assertEquals(listOf("garden", "front_door"), outside.names())

            viewModel.switchTo("office")
            assertEquals(null, viewModel.cameras("office").selectedGroup)
        }

    @Test
    fun remembersTheCamerasSwitchedOffInAGroup() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            val viewModel = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            viewModel.cameras("home")

            viewModel.showCameras("Outside", setOf("front_door"))
            val outside = viewModel.state.first { it.selectedGroup == "Outside" && it.names().size == 1 }
            assertEquals(listOf("front_door"), outside.names())

            // Another screen finds the same choice, and the other groups are untouched.
            val second = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            second.cameras("home")
            second.selectGroup("Outside")
            assertEquals(listOf("front_door"), second.state.first { it.selectedGroup == "Outside" }.names())
            second.selectGroup(null)
            assertEquals(listOf("front_door", "garage"), second.state.first { it.selectedGroup == null }.names())
        }

    @Test
    fun showsTheProblemAndRecoversOnRetry() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            fixture.home.online = false
            val viewModel = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            val failed = viewModel.state.first { it.content is CamerasContent.Failed }
            assertEquals(CamerasContent.Failed(ConnectionProblem.Unreachable), failed.content)

            fixture.home.online = true
            viewModel.retry()

            viewModel.cameras("home")
        }

    @Test
    fun keepsTheCamerasWhileOfflineAndReadsTheServerAgainOnReturn() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            val viewModel = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            viewModel.cameras("home")

            fixture.monitor.status.value = NetworkStatus(isOnline = false)
            val offline = viewModel.state.first { it.isOffline }
            assertEquals(listOf("front_door", "garage"), offline.names())

            // Another screen opened meanwhile shows the same cameras.
            fixture.home.online = false
            val second = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            assertEquals(listOf("front_door", "garage"), second.cameras("home").names())
            fixture.home.online = true

            fixture.home.config = """{"cameras": {"porch": {}}}"""
            fixture.home.allowedCameras = listOf("porch")
            fixture.monitor.status.value = NetworkStatus(isOnline = true, id = 2)

            viewModel.state.first { it.content is CamerasContent.Ready && it.names() == listOf("porch") }
        }

    @Test
    fun reportsThatTheActiveServerWasDeleted() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            val viewModel = CamerasViewModel(fixture.registry, fixture.settings, fixture.monitor)
            viewModel.cameras("home")

            fixture.registry.remove("home")

            viewModel.state.first { it.content == CamerasContent.NoServer }
        }
}
