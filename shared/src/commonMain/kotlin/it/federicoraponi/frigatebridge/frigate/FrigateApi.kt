package it.federicoraponi.frigatebridge.frigate

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.setCookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Credentials(
    val username: String,
    val password: String,
)

/** Where the session token of one server is kept. */
interface TokenStore {
    suspend fun read(): String?

    suspend fun write(token: String?)
}

internal val frigateJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/**
 * Client for one Frigate server reached at one address. It is the only code that knows Frigate's endpoints.
 *
 * Frigate returns the login token in a cookie and does not renew tokens sent as `Authorization: Bearer`,
 * so a 401 triggers a new login with the stored credentials, once for all the requests waiting on it.
 */
class FrigateApi(
    private val http: HttpClient,
    private val baseUrl: String,
    private val credentials: suspend () -> Credentials?,
    private val tokens: TokenStore,
) {
    private val loginMutex = Mutex()

    // Set after the server refuses the credentials, so that they are not sent again and again.
    private var loginFailure: FrigateError? = null

    /** Also the cheapest way to check address, certificate and login: Frigate protects this endpoint too. */
    suspend fun version(): String = get("version").trim().trim('"')

    suspend fun userProfile(): UserProfileDto = decode(get("profile"))

    suspend fun config(): ConfigDto = decode(get("config"))

    suspend fun stats(): StatsDto = decode(get("stats"))

    suspend fun frigateProfiles(): FrigateProfilesDto = decode(get("profiles"))

    /** Allows a new login attempt after a failed one. Call it when the user asks to retry. */
    suspend fun resetLoginFailure() {
        loginMutex.withLock { loginFailure = null }
    }

    private suspend fun get(path: String): String {
        val usedToken = tokens.read()
        var response = send(HttpMethod.Get, path, usedToken)
        if (response.status == HttpStatusCode.Unauthorized) {
            val freshToken = renewToken(staleToken = usedToken)
            response = send(HttpMethod.Get, path, freshToken)
            if (response.status == HttpStatusCode.Unauthorized) throw FrigateError.LoginRejected()
        }
        if (!response.status.isSuccess()) {
            throw FrigateError.UnexpectedResponse("HTTP ${response.status.value} on /api/$path")
        }
        return transport { response.bodyAsText() }
    }

    private suspend fun renewToken(staleToken: String?): String? =
        loginMutex.withLock {
            val current = tokens.read()
            // Another request already logged in while this one was waiting.
            if (current != null && current != staleToken) return@withLock current
            loginFailure?.let { throw it }
            try {
                login().also { tokens.write(it) }
            } catch (error: FrigateError) {
                if (error is FrigateError.LoginRejected || error is FrigateError.RateLimited) loginFailure = error
                throw error
            }
        }

    /** Returns the new token, or `null` if the server has authentication turned off. */
    private suspend fun login(): String? {
        val stored = credentials()
        if (stored == null || stored.username.isBlank()) throw FrigateError.LoginRequired()
        val body =
            buildJsonObject {
                put("user", stored.username)
                put("password", stored.password)
            }
        val response = send(HttpMethod.Post, "login", token = null, jsonBody = body.toString())
        return when {
            response.status == HttpStatusCode.Unauthorized -> throw FrigateError.LoginRejected()
            response.status == HttpStatusCode.TooManyRequests -> throw FrigateError.RateLimited()
            response.status == HttpStatusCode.NotFound -> null
            !response.status.isSuccess() ->
                throw FrigateError.UnexpectedResponse("HTTP ${response.status.value} on /api/login")
            else -> sessionToken(response)
        }
    }

    // The cookie name is configurable on the server, so the token is recognised by its JWT shape.
    private fun sessionToken(response: HttpResponse): String =
        response.setCookie().firstOrNull { cookie -> cookie.value.count { it == '.' } == 2 }?.value
            ?: throw FrigateError.UnexpectedResponse("login succeeded but no session cookie was returned")

    private suspend fun send(
        method: HttpMethod,
        path: String,
        token: String?,
        jsonBody: String? = null,
    ): HttpResponse =
        transport {
            http.request("${baseUrl.trimEnd('/')}/api/$path") {
                this.method = method
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (method != HttpMethod.Get) header("X-CSRF-TOKEN", "1")
                if (jsonBody != null) setBody(TextContent(jsonBody, ContentType.Application.Json))
            }
        }

    private inline fun <T> transport(block: () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw findCertificateRejection(failure)?.let { FrigateError.UntrustedCertificate(it.sha256Fingerprint) }
                ?: FrigateError.Unreachable(failure)
        }

    private inline fun <reified T> decode(body: String): T =
        try {
            frigateJson.decodeFromString<T>(body)
        } catch (failure: SerializationException) {
            throw FrigateError.UnexpectedResponse("the answer is not the expected JSON", failure)
        } catch (failure: IllegalArgumentException) {
            throw FrigateError.UnexpectedResponse("the answer is not the expected JSON", failure)
        }
}
