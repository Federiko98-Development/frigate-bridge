package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerListViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    @Test
    fun showsTheStatusOfEachServer() =
        runTest {
            fixture.saveBoth()
            fixture.office.password = "changed-on-the-server"
            val viewModel = ServerListViewModel(fixture.registry)

            val state =
                viewModel.state.first { state ->
                    state.servers.size == 2 && state.servers.none { it.status == ServerStatus.Checking }
                }

            assertEquals(
                listOf(ServerStatus.Online, ServerStatus.Unavailable(ConnectionProblem.LoginRejected)),
                state.servers.map { it.status },
            )
        }

    @Test
    fun togglesTheDefaultServer() =
        runTest {
            fixture.saveBoth()
            val viewModel = ServerListViewModel(fixture.registry)

            viewModel.toggleDefault("office")
            viewModel.state.first { state -> state.servers.any { it.id == "office" && it.isDefault } }

            viewModel.toggleDefault("office")
            viewModel.state.first { state -> state.servers.size == 2 && state.servers.none { it.isDefault } }
        }

    @Test
    fun checksAServerAddedLater() =
        runTest {
            val viewModel = ServerListViewModel(fixture.registry)
            viewModel.state.first { !it.isLoading }

            fixture.registry.save(fixture.homeServer, fixture.home.password)

            viewModel.state.first { it.servers.singleOrNull()?.status == ServerStatus.Online }
        }
}
