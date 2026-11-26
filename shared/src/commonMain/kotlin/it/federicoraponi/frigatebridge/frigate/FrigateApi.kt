package it.federicoraponi.frigatebridge.frigate

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
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

private const val MAX_ERROR_LENGTH = 200
private const val PREVIEW_QUALITY = 60

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

    /** Turns a function of a camera on or off, until Frigate restarts. Needs the administrator role. */
    suspend fun setCameraFeature(
        camera: String,
        feature: String,
        enabled: Boolean,
    ) {
        val body = buildJsonObject { put("value", if (enabled) "ON" else "OFF") }
        call(HttpMethod.Put, "camera/${camera.encodeURLPathPart()}/set/$feature", body.toString())
    }

    /** Empty when the camera cannot be moved. */
    suspend fun ptzInfo(camera: String): PtzInfoDto = decode(get("${camera.encodeURLPathPart()}/ptz/info"))

    /** Opens the WebSocket, logging in again first if the token has expired. */
    suspend fun openSocket(): FrigateSocket {
        val headers = authorizationHeaders()
        return transport {
            FrigateSocket(
                http.webSocketSession("${baseUrl.trimEnd('/')}/ws".replaceFirst("http", "ws")) {
                    headers.forEach { (name, value) -> header(name, value) }
                },
            )
        }
    }

    suspend fun stats(): StatsDto = decode(get("stats"))

    suspend fun frigateProfiles(): FrigateProfilesDto = decode(get("profiles"))

    /** The most recent picture of [camera] as a JPEG [height] pixels high. */
    suspend fun latestFrame(
        camera: String,
        height: Int,
    ): ByteArray {
        val path = "${camera.encodeURLPathPart()}/latest.jpg?height=$height&quality=$PREVIEW_QUALITY"
        val response = authorized(HttpMethod.Get, path)
        return transport { response.readRawBytes() }
    }

    /** Recorded segments of [camera] between two times in seconds since the epoch. */
    suspend fun recordings(
        camera: String,
        after: Long,
        before: Long,
    ): List<RecordingDto> = decode(get("${camera.encodeURLPathPart()}/recordings?after=$after&before=$before"))

    /** Sends a WebRTC offer for a go2rtc [stream] and returns the answer. */
    suspend fun webRtcAnswer(
        stream: String,
        offerSdp: String,
    ): String {
        val offer = frigateJson.encodeToString(SessionDescriptionDto("offer", offerSdp))
        val path = "go2rtc/webrtc?src=${stream.encodeURLParameter()}"
        return decode<SessionDescriptionDto>(call(HttpMethod.Post, path, offer)).sdp
    }

    /** The HLS playlist of a recorded interval. It is served outside `/api` and needs [authorizationHeaders]. */
    fun recordingPlaylistUrl(
        camera: String,
        start: Long,
        end: Long,
    ): String = "${baseUrl.trimEnd('/')}/vod/${camera.encodeURLPathPart()}/start/$start/end/$end/master.m3u8"

    /**
     * The WebSocket on which go2rtc sends a [stream] as fragmented MP4, over the same HTTPS connection as the
     * rest of the app. It needs [authorizationHeaders].
     */
    fun mp4StreamUrl(stream: String): String =
        "${baseUrl.trimEnd('/')}/live/mse/api/ws?src=${stream.encodeURLParameter()}"

    /** Headers for a player that downloads media by itself. Logs in again first if the token has expired. */
    suspend fun authorizationHeaders(): Map<String, String> {
        version()
        return tokens.read()?.let { mapOf(HttpHeaders.Authorization to "Bearer $it") }.orEmpty()
    }

    /** Allows a new login attempt after a failed one. Call it when the user asks to retry. */
    suspend fun resetLoginFailure() {
        loginMutex.withLock { loginFailure = null }
    }

    private suspend fun get(path: String): String = call(HttpMethod.Get, path)

    private suspend fun call(
        method: HttpMethod,
        path: String,
        jsonBody: String? = null,
    ): String {
        val response = authorized(method, path, jsonBody)
        return transport { response.bodyAsText() }
    }

    /** A successful answer, after logging in again if the token was refused. */
    private suspend fun authorized(
        method: HttpMethod,
        path: String,
        jsonBody: String? = null,
    ): HttpResponse {
        val usedToken = tokens.read()
        var response = send(method, path, usedToken, jsonBody)
        if (response.status == HttpStatusCode.Unauthorized) {
            val freshToken = renewToken(staleToken = usedToken)
            response = send(method, path, freshToken, jsonBody)
            if (response.status == HttpStatusCode.Unauthorized) throw FrigateError.LoginRejected()
        }
        if (!response.status.isSuccess()) {
            // The server's explanation helps, e.g. go2rtc saying that no codec matches.
            val reason = transport { response.bodyAsText() }.trim().take(MAX_ERROR_LENGTH)
            val endpoint = path.substringBefore('?')
            throw FrigateError.UnexpectedResponse("HTTP ${response.status.value} on /api/$endpoint $reason".trim())
        }
        return response
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
                // The proxy caches answers for a few seconds, which would hide a setting just changed.
                if (path == "config") header("X-Cache-Bypass", "1")
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
