package it.federicoraponi.frigatebridge.frigate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrigateApiTest {
    private val server = FakeFrigate()
    private val tokens = InMemoryTokenStore()

    private fun api(
        password: String = server.password,
        username: String = server.username,
        http: HttpClient = server.client(),
        baseUrl: String = "https://frigate.example.com",
    ) = FrigateApi(http, baseUrl, { Credentials(username, password) }, tokens)

    @Test
    fun logsInOnTheFirstUnauthorizedAnswerAndKeepsTheCookieToken() =
        runTest {
            assertEquals("0.18.3-abc1234", api().version())

            assertEquals(1, server.loginCount)
            assertEquals("header.payload1.signature", tokens.token)
            assertEquals("Bearer ${tokens.token}", server.requests.last().headers[HttpHeaders.Authorization])
        }

    @Test
    fun readsTheTokenWhateverTheCookieIsCalled() =
        runTest {
            server.cookieName = "my_cookie"

            api().version()

            assertEquals("header.payload1.signature", tokens.token)
        }

    @Test
    fun onlyTheLoginRequestCarriesTheCsrfHeader() =
        runTest {
            api().version()

            val login = server.requests.single { it.method == HttpMethod.Post }
            assertEquals("1", login.headers["X-CSRF-TOKEN"])
            assertNull(server.requests.last().headers["X-CSRF-TOKEN"])
        }

    @Test
    fun logsInAgainWhenTheTokenExpires() =
        runTest {
            val api = api()
            api.version()
            server.expireSession()

            api.config()

            assertEquals(2, server.loginCount)
        }

    @Test
    fun parallelRequestsShareASingleLogin() =
        runTest {
            server.responseDelayMillis = 10
            val api = api()

            listOf(
                async { api.version() },
                async { api.config() },
                async { api.userProfile() },
                async { api.stats() },
            ).awaitAll()

            assertEquals(1, server.loginCount)
        }

    @Test
    fun aWrongPasswordIsSentOnlyOnceUntilTheUserRetries() =
        runTest {
            server.responseDelayMillis = 10
            val api = api(password = "wrong")

            val failures =
                listOf(
                    async { runCatching { api.version() }.exceptionOrNull() },
                    async { runCatching { api.config() }.exceptionOrNull() },
                ).awaitAll()
            assertFailsWith<FrigateError.LoginRejected> { api.version() }

            assertEquals(2, failures.count { it is FrigateError.LoginRejected })
            assertEquals(1, server.loginCount)

            api.resetLoginFailure()
            assertFailsWith<FrigateError.LoginRejected> { api.version() }
            assertEquals(2, server.loginCount)
        }

    @Test
    fun reportsTheLoginRateLimit() =
        runTest {
            server.loginStatus = HttpStatusCode.TooManyRequests

            assertFailsWith<FrigateError.RateLimited> { api().version() }
        }

    @Test
    fun asksForCredentialsWhenNoneAreStored() =
        runTest {
            assertFailsWith<FrigateError.LoginRequired> { api(username = "").version() }

            assertEquals(0, server.loginCount)
        }

    @Test
    fun worksWithoutLoginWhenAuthenticationIsOff() =
        runTest {
            server.authEnabled = false

            assertEquals("0.18.3-abc1234", api(username = "").version())
            assertEquals(0, server.loginCount)
        }

    @Test
    fun reportsAServerThatDoesNotAnswer() =
        runTest {
            server.online = false

            assertFailsWith<FrigateError.Unreachable> { api().version() }
        }

    @Test
    fun reportsARejectedCertificateWithItsFingerprint() =
        runTest {
            class Rejected :
                RuntimeException(),
                CertificateRejection {
                override val sha256Fingerprint = "AA:BB"
            }
            val http = HttpClient(MockEngine { throw RuntimeException("handshake failed", Rejected()) })

            val error = assertFailsWith<FrigateError.UntrustedCertificate> { api(http = http).version() }

            assertEquals("AA:BB", error.sha256Fingerprint)
        }

    @Test
    fun findsACertificateRejectionHiddenBehindAnotherAddressFailing() {
        class Rejected :
            RuntimeException(),
            CertificateRejection {
            override val sha256Fingerprint = "AA:BB"
        }
        val failure = RuntimeException("connection refused")
        failure.addSuppressed(RuntimeException("handshake failed", Rejected()))

        assertEquals("AA:BB", findCertificateRejection(failure)?.sha256Fingerprint)
    }

    @Test
    fun reportsAPageThatIsNotFrigate() =
        runTest {
            val http = HttpClient(MockEngine { respond("<html>Welcome</html>") })

            assertFailsWith<FrigateError.UnexpectedResponse> { api(http = http).config() }
        }

    @Test
    fun keepsTheBasePathOfAReverseProxy() =
        runTest {
            api(baseUrl = "https://home.example.com/frigate/").version()

            assertEquals(
                "/frigate/api/version",
                server.requests
                    .last()
                    .url.encodedPath,
            )
        }

    @Test
    fun parsesTheConfigIncludingBothCameraListShapes() =
        runTest {
            val config = api().config()

            assertEquals(setOf("front_door", "garden", "garage"), config.cameras.keys)
            assertEquals("Front door", config.cameras.getValue("front_door").friendlyName)
            assertEquals(false, config.cameras.getValue("garage").enabled)
            assertEquals(listOf("garden", "front_door"), config.cameraGroups.getValue("Outside").cameras)
            assertEquals(listOf("garage"), config.cameraGroups.getValue("Indoor").cameras)
        }

    @Test
    fun parsesStatsAndFrigateProfiles() =
        runTest {
            val api = api()

            assertEquals(120, api.stats().service.uptime)
            assertNull(api.frigateProfiles().activeProfile)
        }
}
