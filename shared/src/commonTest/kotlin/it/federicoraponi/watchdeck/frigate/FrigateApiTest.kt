package it.federicoraponi.watchdeck.frigate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import it.federicoraponi.watchdeck.camera.EditableGroup
import it.federicoraponi.watchdeck.camera.groupChange
import it.federicoraponi.watchdeck.camera.groupOrderChange
import it.federicoraponi.watchdeck.camera.groupRemoval
import it.federicoraponi.watchdeck.server.ROLES_TOPIC
import it.federicoraponi.watchdeck.server.roleChange
import it.federicoraponi.watchdeck.server.roleRemoval
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
    fun readsTheUsersAndTakesAMissingRoleForViewer() =
        runTest {
            val body = """[{"username": "admin", "role": "admin"}, {"username": "guest", "role": null}]"""
            val http = HttpClient(MockEngine { respond(body) })

            assertEquals(listOf(UserDto("admin", "admin"), UserDto("guest", "viewer")), api(http = http).users())
        }

    @Test
    fun createsAUser() =
        runTest {
            api().createUser("guest", "made-up-password", "viewer")

            val request = server.requests.last()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/users", request.url.encodedPath)
            assertEquals(
                """{"username":"guest","password":"made-up-password","role":"viewer"}""",
                request.body.toByteArray().decodeToString(),
            )
            assertEquals("viewer", server.users["guest"])
        }

    @Test
    fun reportsFrigatesExplanationOfARefusal() =
        runTest {
            val error =
                assertFailsWith<FrigateError.UnexpectedResponse> { api().createUser("guest", "short", "viewer") }

            assertEquals(400, error.status)
            assertEquals("Password must be at least 12 characters long", error.serverMessage)
        }

    @Test
    fun aWrongCurrentPasswordDoesNotCauseANewLogin() =
        runTest {
            server.users[server.username] = "viewer"
            val api = api()
            api.version()
            val logins = server.loginCount

            val error =
                assertFailsWith<FrigateError.UnexpectedResponse> {
                    api.setPassword(server.username, "another-made-up-password", "not-the-password")
                }

            assertEquals(401, error.status)
            assertEquals("Current password is incorrect", error.serverMessage)
            assertEquals(logins, server.loginCount)
            assertEquals(1, server.requests.count { it.url.encodedPath.endsWith("/password") })
        }

    @Test
    fun aWrongCurrentPasswordAfterARenewedLoginIsNotARejectedLogin() =
        runTest {
            var passwordRequests = 0
            val http =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath.endsWith("/version") -> respond("0.18.3")
                            request.url.encodedPath.endsWith("/login") ->
                                respond("", headers = headersOf(HttpHeaders.SetCookie, "token=a.b.c"))
                            // First as the proxy refuses an expired token, then as Frigate refuses the password.
                            ++passwordRequests == 1 -> respond("", HttpStatusCode.Unauthorized)
                            else ->
                                respond(
                                    """{"message":"Current password is incorrect"}""",
                                    HttpStatusCode.Unauthorized,
                                )
                        }
                    },
                )

            val error =
                assertFailsWith<FrigateError.UnexpectedResponse> {
                    api(http = http).setPassword("guest", "another-made-up-password", "not-the-password")
                }

            assertEquals(401, error.status)
            assertEquals(2, passwordRequests)
        }

    @Test
    fun changingOnesOwnPasswordReturnsTheNewToken() =
        runTest {
            val api = api()

            val token = api.setPassword(server.username, "another-made-up-password", server.password)

            assertEquals("header.own1.signature", token)
            assertEquals("another-made-up-password", server.password)
        }

    @Test
    fun settingAnotherPasswordSendsNoOldPasswordAndReturnsNoToken() =
        runTest {
            server.users["guest"] = "viewer"

            assertNull(api().setPassword("guest", "another-made-up-password", oldPassword = null))

            assertEquals(
                """{"password":"another-made-up-password"}""",
                server.requests
                    .last()
                    .body
                    .toByteArray()
                    .decodeToString(),
            )
        }

    @Test
    fun renamesAGroupDeletingTheOldNameFirst() =
        runTest {
            server.config = """{"camera_groups": {"Old_name": {"cameras": ["garden"], "order": 3}}}"""
            val group = EditableGroup("New_name", listOf("front_door", "garden"), "LuCar", 3)

            api().setConfig(groupChange("Old_name", group))

            assertEquals(
                """{"requires_restart":0,"config_data":{"camera_groups":{"Old_name":"",""" +
                    """"New_name":{"order":3,"icon":"LuCar","cameras":["front_door","garden"]}}}}""",
                server.configSetBodies.single(),
            )
            assertEquals(setOf("New_name"), api().config().cameraGroups.keys)
        }

    @Test
    fun writesOnlyTheRoleThatChangesAndAnnouncesIt() =
        runTest {
            val api = api()

            api.setConfig(roleChange("garden_only", listOf("garden")), ROLES_TOPIC)
            assertEquals(mapOf("garden_only" to listOf("garden")), api.config().auth.roles)
            api.setConfig(roleRemoval("garden_only"), ROLES_TOPIC)

            assertEquals(
                listOf(
                    """{"requires_restart":0,"config_data":{"auth":{"roles":{"garden_only":["garden"]}}},""" +
                        """"update_topic":"config/auth"}""",
                    """{"requires_restart":0,"config_data":{"auth":{"roles":{"garden_only":""}}},""" +
                        """"update_topic":"config/auth"}""",
                ),
                server.configSetBodies,
            )
            assertEquals(emptyMap(), api.config().auth.roles)
        }

    @Test
    fun deletesAndReordersGroups() =
        runTest {
            val api = api()

            api.setConfig(groupOrderChange(listOf("Outside", "Indoor")))
            val reordered = api.config().cameraGroups
            assertEquals(1, reordered.getValue("Outside").order)
            assertEquals(2, reordered.getValue("Indoor").order)
            assertEquals("tree", reordered.getValue("Outside").icon)
            api.setConfig(groupRemoval("Outside"))

            assertEquals(setOf("Indoor"), api.config().cameraGroups.keys)
            assertEquals(
                """{"requires_restart":0,"config_data":{"camera_groups":{"Outside":""}}}""",
                server.configSetBodies.last(),
            )
        }

    @Test
    fun skipsTheProxyCacheOnlyWhenReadingUsersAndConfig() =
        runTest {
            val api = api()

            api.users()
            assertEquals("1", server.requests.last().headers["X-Cache-Bypass"])
            api.config()
            assertEquals("1", server.requests.last().headers["X-Cache-Bypass"])
            api.createUser("guest", "made-up-password", "viewer")
            assertNull(server.requests.last().headers["X-Cache-Bypass"])
            api.version()
            assertNull(server.requests.last().headers["X-Cache-Bypass"])
        }

    @Test
    fun parsesStatsAndFrigateProfiles() =
        runTest {
            val api = api()

            assertEquals(120, api.stats().service.uptime)
            assertNull(api.frigateProfiles().activeProfile)
        }
}
