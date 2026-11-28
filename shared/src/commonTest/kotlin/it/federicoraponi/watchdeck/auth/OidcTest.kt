package it.federicoraponi.watchdeck.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import it.federicoraponi.watchdeck.frigate.FrigateApi
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.frigate.PasswordAuthenticator
import it.federicoraponi.watchdeck.frigate.ProviderFailure
import it.federicoraponi.watchdeck.server.FakeBrowser
import it.federicoraponi.watchdeck.server.NOW
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUER = "https://id.example.com/realms/demo"
private const val FRIGATE = "https://frigate.example.com"

private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

private fun jwt(claims: String): String =
    listOf("""{"alg":"RS256"}""", claims, "signature").joinToString(".") { encoder.encode(it.encodeToByteArray()) }

/** An identity provider, and a proxy in front of Frigate that takes only its latest access token. */
private class FakeProvider {
    var issued = 0
    var accessToken = ""
    var nonce = ""
    var refreshFails = false
    var proxyAccepts = true
    var proxyStatus = HttpStatusCode.Found
    var loginPage = "$ISSUER/auth?client_id=proxy"
    val tokenRequests = mutableListOf<Map<String, String>>()

    fun client(): HttpClient = HttpClient(MockEngine { request -> handle(request) }) { followRedirects = false }

    fun tokens(lifetime: Long = 300): OidcTokens {
        issued++
        accessToken = jwt("""{"n":$issued,"exp":${NOW + lifetime}}""")
        return OidcTokens(accessToken, refreshToken = "refresh-$issued", accessExpiresAt = NOW + lifetime)
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        return when {
            path.endsWith("/.well-known/openid-configuration") ->
                json(
                    """{"issuer":"$ISSUER","authorization_endpoint":"$ISSUER/auth",""" +
                        """"token_endpoint":"$ISSUER/token"}""",
                )
            path.endsWith("/token") -> token(request)
            request.headers[HttpHeaders.Authorization] == "Bearer $accessToken" && proxyAccepts -> respond("0.18.3")
            proxyStatus == HttpStatusCode.Found ->
                respond("", proxyStatus, headersOf(HttpHeaders.Location, loginPage))
            else ->
                respond(
                    "<form action=\"/oauth2/start\">",
                    proxyStatus,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                )
        }
    }

    private suspend fun MockRequestHandleScope.token(request: HttpRequestData): HttpResponseData {
        val form = parseQueryString(request.body.toByteArray().decodeToString())
        val sent = form.names().associateWith { form[it].orEmpty() }
        tokenRequests += sent
        if (refreshFails) return json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
        val tokens = tokens()
        val identity = jwt("""{"iss":"$ISSUER","aud":"watchdeck","nonce":"$nonce","exp":${NOW + 300}}""")
        return json(
            """{"access_token":"${tokens.accessToken}","refresh_token":"${tokens.refreshToken}",""" +
                """"id_token":"$identity","expires_in":300}""",
        )
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
}

class OidcTest {
    private val provider = FakeProvider()
    private val settings = OidcSettings(ISSUER)
    private val client = OidcClient(lazy { provider.client() }) { NOW }
    private val store = MemoryOidcTokenStore()
    private val browser = FakeBrowser()
    private val signIn = OidcSignIn(client, browser)

    private fun api() = FrigateApi(provider.client(), FRIGATE, OidcAuthenticator(settings, client, store) { NOW })

    @Test
    fun derivesTheChallengeAsRfc7636Does() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun sendsTokensOnlyOverHttpsOrToThePhoneItself() {
        assertTrue(isAcceptableProviderUrl(ISSUER))
        assertTrue(isAcceptableProviderUrl("http://localhost:8080/realms/demo"))
        assertFalse(isAcceptableProviderUrl("http://id.example.com/realms/demo"))
        assertFalse(isAcceptableProviderUrl("https://user@id.example.com"))
        assertFalse(isAcceptableProviderUrl("id.example.com"))
    }

    @Test
    fun findsTheIssuerBehindAKeycloakSignInPage() {
        assertEquals(ISSUER, issuerFromLoginUrl("$ISSUER/protocol/openid-connect/auth?client_id=proxy"))
        assertNull(issuerFromLoginUrl("https://login.example.com/authorize?client_id=proxy"))
    }

    @Test
    fun signsInThroughTheBrowserWithPkce() =
        runTest {
            browser.onOpen = { page ->
                val asked = Url(page).parameters
                provider.nonce = asked["nonce"].orEmpty()
                signIn.handleRedirect("${browser.redirectUri}?code=the-code&state=${asked["state"]}")
            }

            val tokens = signIn.signIn(settings)

            val asked = Url(browser.opened.single()).parameters
            assertEquals("S256", asked["code_challenge_method"])
            assertEquals("watchdeck", asked["client_id"])
            val exchange = provider.tokenRequests.single()
            assertEquals("the-code", exchange["code"])
            assertEquals(asked["code_challenge"], pkceChallenge(exchange.getValue("code_verifier")))
            assertEquals(provider.accessToken, tokens.accessToken)
        }

    @Test
    fun takesNoRedirectItDidNotAskFor() =
        runTest {
            var wasTaken = true
            browser.onOpen = {
                wasTaken = signIn.handleRedirect("${browser.redirectUri}?code=planted&state=guessed")
                signIn.cancel()
            }

            val failure = assertFailsWith<FrigateError.ProviderFailed> { signIn.signIn(settings) }

            assertFalse(wasTaken)
            assertEquals(ProviderFailure.Cancelled, failure.reason)
            assertTrue(provider.tokenRequests.isEmpty())
        }

    @Test
    fun endsTheWaitWhenTheUserComesBackFromTheBrowserWithoutAnAnswer() =
        runTest {
            val attempt = async { runCatching { signIn.signIn(settings) } }
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()

            signIn.returnedWithoutAnswer()

            val failure = attempt.await().exceptionOrNull() as FrigateError.ProviderFailed
            assertEquals(ProviderFailure.Cancelled, failure.reason)
        }

    @Test
    fun refusesAnIdTokenThatAnswersAnotherRequest() =
        runTest {
            browser.onOpen = { page ->
                provider.nonce = "someone-else"
                signIn.handleRedirect("${browser.redirectUri}?code=c&state=${Url(page).parameters["state"]}")
            }

            val failure = assertFailsWith<FrigateError.ProviderFailed> { signIn.signIn(settings) }

            assertEquals(ProviderFailure.Misconfigured, failure.reason)
        }

    @Test
    fun renewsAnEndingTokenBeforeSendingIt() =
        runTest {
            store.write(provider.tokens(lifetime = 30))

            assertEquals("0.18.3", api().version())

            assertEquals("refresh_token", provider.tokenRequests.single()["grant_type"])
            assertEquals("refresh-2", store.read()?.refreshToken)
        }

    @Test
    fun renewsOnceForRequestsRefusedTogether() =
        runTest {
            store.write(provider.tokens())
            // The proxy has meanwhile stopped taking it.
            provider.accessToken = "another"
            val api = api()

            List(4) { async { api.version() } }.awaitAll()

            assertEquals(1, provider.tokenRequests.size)
        }

    @Test
    fun asksToSignInAgainOnceTheProviderEndsTheSession() =
        runTest {
            store.write(provider.tokens(lifetime = 30))
            provider.refreshFails = true

            assertFailsWith<FrigateError.SignInRequired> { api().version() }

            assertNull(store.read())
        }

    @Test
    fun tellsThatTheProxyDoesNotTakeAFreshToken() =
        runTest {
            store.write(provider.tokens())
            provider.proxyAccepts = false

            assertFailsWith<FrigateError.SignInRefused> { api().version() }
        }

    @Test
    fun seesASignInPageOnTheSameHostAsTheServer() =
        runTest {
            store.write(provider.tokens())
            provider.proxyAccepts = false
            provider.loginPage = "$FRIGATE/realms/demo/protocol/openid-connect/auth?client_id=proxy&response_type=code"

            assertFailsWith<FrigateError.SignInRefused> { api().version() }
        }

    @Test
    fun readsAProxyPageWithStatus403AsARefusalOfTheSignIn() =
        runTest {
            store.write(provider.tokens())
            provider.proxyAccepts = false
            provider.proxyStatus = HttpStatusCode.Forbidden

            val failure = assertFailsWith<FrigateError.SignInRefused> { api().version() }

            assertEquals(403, failure.status)
        }

    @Test
    fun seesTheSignInPageOfAProxyThatDoesNotRedirect() =
        runTest {
            provider.proxyStatus = HttpStatusCode.Forbidden
            val tokens =
                object : it.federicoraponi.watchdeck.frigate.TokenStore {
                    override suspend fun read(): String? = null

                    override suspend fun write(token: String?) = Unit
                }
            val api = FrigateApi(provider.client(), FRIGATE, PasswordAuthenticator({ null }, tokens))

            assertFailsWith<FrigateError.ExternalSignIn> { api.version() }
        }

    @Test
    fun neverSendsThePasswordWhenTheServerAsksToSignInElsewhere() =
        runTest {
            var asked = false
            val authenticator =
                PasswordAuthenticator(
                    credentials = {
                        asked = true
                        null
                    },
                    tokens =
                        object : it.federicoraponi.watchdeck.frigate.TokenStore {
                            override suspend fun read(): String? = null

                            override suspend fun write(token: String?) = Unit
                        },
                )

            val failure =
                assertFailsWith<FrigateError.ExternalSignIn> {
                    FrigateApi(provider.client(), FRIGATE, authenticator).version()
                }

            assertEquals("$ISSUER/auth?client_id=proxy", failure.loginUrl)
            assertFalse(asked)
        }
}
