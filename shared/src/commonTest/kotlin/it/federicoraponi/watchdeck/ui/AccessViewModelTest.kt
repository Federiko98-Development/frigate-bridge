package it.federicoraponi.watchdeck.ui

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import it.federicoraponi.watchdeck.frigate.FakeFrigate
import it.federicoraponi.watchdeck.server.Fixture
import it.federicoraponi.watchdeck.server.NameProblem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccessViewModelTest : ViewModelTest() {
    private val fixture = Fixture()
    private val home = fixture.home

    private suspend fun open(): AccessViewModel {
        home.config =
            FakeFrigate.SAMPLE_CONFIG.replaceFirst(
                "{",
                """{"auth": {"roles": {"garden_only": ["garden"]}},""",
            )
        fixture.saveBoth()
        fixture.registry.activate("home")
        val viewModel = AccessViewModel(fixture.registry)
        viewModel.state.first { !it.isLoading }
        return viewModel
    }

    private suspend fun AccessViewModel.outcome(): AccessResult {
        val result = checkNotNull(state.first { it.running == null && it.result != null }.result)
        dismissResult()
        return result
    }

    private fun changes(): List<HttpRequestData> =
        home.requests.filter { it.method != HttpMethod.Get && it.url.encodedPath != "/api/login" }

    @Test
    fun listsUsersAndRoles() =
        runTest {
            val state = open().state.value

            assertEquals(listOf("admin", "viewer"), state.users.map { it.username })
            val self = state.users.single { it.isSelf }
            assertEquals("viewer", self.username)
            assertFalse(self.canDelete)
            assertFalse(self.canChangeRole)
            assertFalse(state.users.first { it.username == "admin" }.canDelete)
            assertEquals(listOf("garden_only"), state.roles.map { it.name })
            assertEquals(listOf("admin", "viewer", "garden_only"), state.roleChoices)
        }

    @Test
    fun createsAUserAndRefusesADuplicateWithoutSendingIt() =
        runTest {
            val viewModel = open()

            viewModel.createUser("alice", "made-up-password", "made-up-password", "garden_only")
            assertIs<AccessResult.Done>(viewModel.outcome())
            val alice =
                viewModel.state.value.users
                    .single { it.username == "alice" }
            assertTrue(alice.canDelete)
            assertEquals("garden_only", alice.role)

            val sent = changes().size
            viewModel.createUser("alice", "made-up-password", "made-up-password", "viewer")
            assertEquals(NameProblem.Taken, assertIs<AccessResult.Invalid>(viewModel.outcome()).name)
            assertEquals(sent, changes().size)
        }

    @Test
    fun changesARoleAPasswordAndDeletes() =
        runTest {
            home.users["alice"] = "viewer"
            val viewModel = open()

            viewModel.changeRole("alice", "admin")
            assertIs<AccessResult.Done>(viewModel.outcome())
            assertEquals("admin", home.users["alice"])

            viewModel.setPassword("alice", "another-password", "another-password")
            assertIs<AccessResult.Done>(viewModel.outcome())
            assertEquals("another-password", home.passwords["alice"])

            viewModel.deleteUser("alice")
            assertIs<AccessResult.Done>(viewModel.outcome())
            assertTrue(
                viewModel.state.value.users
                    .none { it.username == "alice" },
            )
        }

    @Test
    fun savesARole() =
        runTest {
            val viewModel = open()

            viewModel.saveRole("entrance", setOf("front_door"), isNew = true)

            assertIs<AccessResult.Done>(viewModel.outcome())
            assertEquals(
                listOf("entrance", "garden_only"),
                viewModel.state.value.roles
                    .map { it.name },
            )
        }

    @Test
    fun makesViewersOfTheHoldersBeforeRemovingARole() =
        runTest {
            home.users["alice"] = "garden_only"
            home.users["bob"] = "garden_only"
            val viewModel = open()

            viewModel.deleteRole("garden_only")

            assertIs<AccessResult.Done>(viewModel.outcome())
            assertEquals(
                listOf("/api/users/alice/role", "/api/users/bob/role", "/api/config/set"),
                changes().map { it.url.encodedPath },
            )
            assertEquals("viewer", home.users["alice"])
            assertEquals("viewer", home.users["bob"])
            assertTrue(
                viewModel.state.value.roles
                    .isEmpty(),
            )
        }

    @Test
    fun saysWhenARemovalWasNotSaved() =
        runTest {
            val viewModel = open()
            home.ignoreConfigWrites = true

            viewModel.deleteRole("garden_only")

            assertEquals(AdminFailure.NotSaved, assertIs<AccessResult.Failed>(viewModel.outcome()).failure)
        }

    @Test
    fun leavesWhenTheAccountIsNoLongerAnAdministrator() =
        runTest {
            val viewModel = open()
            val logins = home.loginCount
            home.users["viewer"] = "viewer"

            viewModel.refresh()

            viewModel.state.first { it.isGone }
            assertEquals(logins + 1, home.loginCount)
            assertEquals(
                "viewer",
                fixture.registry
                    .session("home")
                    ?.account
                    ?.role,
            )
        }

    @Test
    fun asksForNoUsersWhenTheServerSignsNobodyIn() =
        runTest {
            home.authEnabled = false
            val viewModel = open()

            assertFalse(viewModel.state.value.authEnabled)
            assertTrue(
                viewModel.state.value.users
                    .isEmpty(),
            )
            assertTrue(home.requests.none { it.url.encodedPath == "/api/users" })
        }
}
