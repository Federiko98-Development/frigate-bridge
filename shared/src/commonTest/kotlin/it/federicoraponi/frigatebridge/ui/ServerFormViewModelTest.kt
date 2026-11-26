package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFormViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    private fun form(serverId: String? = null) =
        ServerFormViewModel(ServerFormArgs(serverId), fixture.registry, fixture.sessions)

    private fun ServerFormViewModel.fillHome(password: String = fixture.home.password) {
        setName("Home")
        setUrl("home.example.com")
        setUsername("viewer")
        setPassword(password)
    }

    private suspend fun ServerFormViewModel.outcome(): ServerFormState =
        state.first {
            it.savedServerId != null || it.problem != null || it.certificatePrompt != null || it.errors.isNotEmpty()
        }

    private fun savedServers() =
        fixture.registry.state.value
            ?.servers
            .orEmpty()

    @Test
    fun savesAServerThatAnswers() =
        runTest {
            val viewModel = form()
            viewModel.fillHome()

            viewModel.save()
            viewModel.outcome()

            val saved = savedServers().single()
            assertEquals("https://home.example.com", saved.url)
            assertEquals(fixture.home.password, fixture.registry.storedPassword(saved.id))
        }

    @Test
    fun pointsOutMissingAndInvalidFields() =
        runTest {
            val viewModel = form()
            viewModel.setUrl("not an address")
            viewModel.setAlternativeUrl("ftp://example.com")
            viewModel.setUsername("viewer")

            viewModel.save()

            assertEquals(FormError.entries.toSet(), viewModel.outcome().errors)
            assertTrue(savedServers().isEmpty())
        }

    @Test
    fun doesNotSaveARejectedPassword() =
        runTest {
            val viewModel = form()
            viewModel.fillHome(password = "wrong")

            viewModel.save()
            val state = viewModel.outcome()

            assertEquals(ConnectionProblem.LoginRejected, state.problem)
            assertEquals(false, state.canSaveAnyway)
            assertTrue(savedServers().isEmpty())
        }

    @Test
    fun canSaveAServerThatIsOffline() =
        runTest {
            fixture.home.online = false
            val viewModel = form()
            viewModel.fillHome()
            viewModel.save()
            assertTrue(viewModel.outcome().canSaveAnyway)

            viewModel.saveAnyway()
            viewModel.state.first { it.savedServerId != null }

            assertEquals("Home", savedServers().single().name)
        }

    @Test
    fun savesASelfSignedServerOnlyAfterTheCertificateIsAccepted() =
        runTest {
            fixture.network.selfSigned["home.example.com"] = "AA:BB"
            val viewModel = form()
            viewModel.fillHome()
            viewModel.save()

            val prompt = viewModel.outcome().certificatePrompt
            assertEquals(CertificatePrompt("AA:BB", replacesAccepted = false), prompt)
            assertTrue(savedServers().isEmpty())
            assertEquals(0, fixture.home.loginCount)

            viewModel.acceptCertificate()
            viewModel.state.first { it.savedServerId != null }

            assertEquals("AA:BB", savedServers().single().certificateSha256)
        }

    @Test
    fun savesNothingWhenTheCertificateIsRefused() =
        runTest {
            fixture.network.selfSigned["home.example.com"] = "AA:BB"
            val viewModel = form()
            viewModel.fillHome()
            viewModel.save()
            viewModel.outcome()

            viewModel.rejectCertificate()

            assertEquals(ConnectionProblem.UntrustedCertificate, viewModel.state.value.problem)
            assertNull(viewModel.state.value.certificatePrompt)
            assertTrue(savedServers().isEmpty())
        }

    @Test
    fun warnsWhenAnAcceptedCertificateChanged() =
        runTest {
            fixture.network.selfSigned["home.example.com"] = "CC:DD"
            fixture.registry.save(fixture.homeServer.copy(certificateSha256 = "AA:BB"), fixture.home.password)
            val viewModel = form("home")

            viewModel.save()

            assertEquals(CertificatePrompt("CC:DD", replacesAccepted = true), viewModel.outcome().certificatePrompt)
        }

    @Test
    fun keepsTheStoredPasswordWhenOnlyTheNameChanges() =
        runTest {
            fixture.saveBoth()
            val viewModel = form("home")
            viewModel.setName("Country house")

            viewModel.save()
            viewModel.outcome()

            assertEquals("Country house", savedServers().first().name)
            assertEquals(fixture.home.password, fixture.registry.storedPassword("home"))
        }

    @Test
    fun asksForThePasswordAgainWhenTheAddressChanges() =
        runTest {
            fixture.saveBoth()
            val viewModel = form("home")
            viewModel.setUrl("https://somewhere-else.example.com")

            viewModel.save()

            assertEquals(setOf(FormError.PasswordMissing), viewModel.outcome().errors)
        }

    @Test
    fun warnsAboutUnencryptedAddresses() =
        runTest {
            val viewModel = form()
            viewModel.setUrl("https://home.example.com")
            assertEquals(false, viewModel.state.value.sendsPasswordUnencrypted)

            viewModel.setAlternativeUrl("http://192.168.1.20:5000")

            assertEquals(true, viewModel.state.value.sendsPasswordUnencrypted)
        }
}
