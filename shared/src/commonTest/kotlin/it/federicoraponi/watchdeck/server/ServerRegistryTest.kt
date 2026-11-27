package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.frigate.FrigateError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerRegistryTest {
    private val fixture = Fixture()
    private val registry = fixture.registry

    private suspend fun connect(serverId: String): ConnectionState = checkNotNull(registry.session(serverId)).refresh()

    private fun ConnectionState.cameraNames(): List<String> =
        assertIs<ConnectionState.Connected>(this)
            .snapshot.catalog.cameras
            .map { it.ref.cameraName }

    @Test
    fun everyServerShowsOnlyItsOwnCameras() =
        runTest {
            fixture.saveBoth()

            val home = connect("home")
            val office = connect("office")

            assertEquals(listOf("front_door", "garden", "garage"), home.cameraNames())
            assertEquals(listOf("front_door", "warehouse"), office.cameraNames())
            val homeDoor =
                assertIs<ConnectionState.Connected>(home)
                    .snapshot.catalog.cameras
                    .first()
                    .ref
            val officeDoor =
                assertIs<ConnectionState.Connected>(office)
                    .snapshot.catalog.cameras
                    .first()
                    .ref
            assertEquals(homeDoor.cameraName, officeDoor.cameraName)
            assertTrue(homeDoor != officeDoor)
        }

    @Test
    fun aServerThatIsDownDoesNotAffectTheOther() =
        runTest {
            fixture.saveBoth()
            fixture.home.online = false

            val home = connect("home")
            val office = connect("office")

            assertIs<FrigateError.Unreachable>(assertIs<ConnectionState.Failed>(home).error)
            assertIs<ConnectionState.Connected>(office)
        }

    @Test
    fun eachServerKeepsItsOwnToken() =
        runTest {
            fixture.saveBoth()

            connect("home")
            connect("office")
            fixture.office.expireSession()
            connect("office")
            connect("home")

            assertEquals(1, fixture.home.loginCount)
            assertEquals(2, fixture.office.loginCount)
            assertEquals("header.payload1.signature", fixture.secrets.values["token.home"])
            assertEquals("header.payload2.signature", fixture.secrets.values["token.office"])
        }

    @Test
    fun fallsBackToTheAlternativeAddress() =
        runTest {
            val server =
                fixture.homeServer.copy(
                    url = "http://192.168.1.20:5000",
                    alternativeUrl = "https://home.example.com",
                )
            registry.save(server, fixture.home.password)

            val state = connect("home")

            assertEquals("https://home.example.com", assertIs<ConnectionState.Connected>(state).snapshot.url)
        }

    @Test
    fun prefersTheFirstAddressWhenItAnswers() =
        runTest {
            fixture.network.hosts["home.local"] = fixture.home
            registry.save(
                fixture.homeServer.copy(url = "https://home.local", alternativeUrl = "https://home.example.com"),
                fixture.home.password,
            )

            val state = connect("home")

            assertEquals("https://home.local", assertIs<ConnectionState.Connected>(state).snapshot.url)
        }

    @Test
    fun flagsAServerOlderThanTheSupportedRelease() =
        runTest {
            fixture.saveBoth()
            fixture.home.version = "0.17.1"

            val state = connect("home")

            assertEquals(false, assertIs<ConnectionState.Connected>(state).snapshot.isSupportedVersion)
        }

    @Test
    fun retriesTheLoginWhenAskedAgain() =
        runTest {
            fixture.saveBoth()
            fixture.home.password = "changed-on-the-server"
            assertIs<ConnectionState.Failed>(connect("home"))

            fixture.home.password = "correct-password"

            assertIs<ConnectionState.Connected>(connect("home"))
        }

    @Test
    fun opensOnTheDefaultServerAfterARestart() =
        runTest {
            fixture.saveBoth()
            registry.setDefault("office")

            val restarted = ServerRegistry(fixture.store, fixture.secrets, fixture.sessions).load()

            assertEquals("office", restarted.activeServerId)
            assertEquals(listOf("home", "office"), restarted.servers.map { it.id })
        }

    @Test
    fun opensOnTheListWithoutADefaultServer() =
        runTest {
            fixture.saveBoth()
            registry.activate("home")

            val restarted = ServerRegistry(fixture.store, fixture.secrets, fixture.sessions).load()

            assertNull(restarted.activeServerId)
        }

    @Test
    fun removingAServerDeletesItsSecretsAndKeepsTheOthers() =
        runTest {
            fixture.saveBoth()
            connect("home")
            connect("office")
            registry.setDefault("home")
            registry.activate("home")

            registry.remove("home")

            val state = checkNotNull(registry.state.value)
            assertEquals(listOf("office"), state.servers.map { it.id })
            assertNull(state.defaultServerId)
            assertNull(state.activeServerId)
            assertNull(registry.session("home"))
            assertEquals(setOf("password.office", "token.office"), fixture.secrets.values.keys)
        }

    @Test
    fun savingAgainReplacesTheSessionEvenIfOnlyThePasswordChanged() =
        runTest {
            fixture.saveBoth()
            val before = registry.session("home")
            val revision = checkNotNull(registry.state.value).revision
            assertSame(before, registry.session("home"))

            registry.save(fixture.homeServer, "new-password")

            assertNotSame(before, registry.session("home"))
            assertTrue(checkNotNull(registry.state.value).revision > revision)
            assertEquals("new-password", registry.storedPassword("home"))
        }

    @Test
    fun theSavedListNeverContainsSecrets() =
        runTest {
            fixture.saveBoth()

            val written = fixture.store.saved.toString()

            assertTrue(fixture.home.password !in written && fixture.office.password !in written)
        }
}
