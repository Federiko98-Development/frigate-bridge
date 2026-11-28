package it.federicoraponi.watchdeck.frigate

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.setCookie
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val MAX_ERROR_LENGTH = 200
private const val PREVIEW_QUALITY = 60
private const val DOWNLOAD_BUFFER = 64 * 1024
private const val API_ROOT = "api/"
private val REDIRECTS = setOf(301, 302, 303, 307, 308)

// Where oauth2-proxy and the proxies like it serve their sign-in.
private const val PROXY_PATH = "/oauth2/"

// What the app changes and reads back at once: the proxy's cache would answer with the old state.
private val UNCACHED = setOf("config", "users", "review")

// Where Frigate keeps the files that its web server publishes at the top level.
private const val MEDIA_ROOT = "/media/frigate/"

internal val frigateJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/**
 * Client for one Frigate server reached at one address. It is the only code that knows Frigate's endpoints.
 *
 * Every request carries the token of the [Authenticator]. A refusal, by Frigate or by a proxy in front of it,
 * asks it for another token, once.
 *
 * Frigate returns its login token in a cookie and does not renew tokens sent as `Authorization: Bearer`.
 */
class FrigateApi(
    private val http: HttpClient,
    private val baseUrl: String,
    private val authenticator: Authenticator,
) {
    /** For a server that signs users in with Frigate's own login. */
    constructor(
        http: HttpClient,
        baseUrl: String,
        credentials: suspend () -> Credentials?,
        tokens: TokenStore,
    ) : this(http, baseUrl, PasswordAuthenticator(credentials, tokens))

    private val passwordLogin = PasswordLogin(::login)

    /** Also the cheapest way to check address, certificate and login: Frigate protects this endpoint too. */
    suspend fun version(): String = get("version").trim().trim('"')

    suspend fun userProfile(): UserProfileDto = decode(get("profile"))

    suspend fun config(): ConfigDto = decode(get("config"))

    /** Every user of the server, by name. Needs the administrator role. */
    suspend fun users(): List<UserDto> = decode(get("users"))

    /** Adds a user. Needs the administrator role. Frigate answers with an error 500 if the name is taken. */
    suspend fun createUser(
        username: String,
        password: String,
        role: String,
    ) {
        val body =
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("role", role)
            }
        call(HttpMethod.Post, "users", body.toString())
    }

    /** Removes a user. Needs the administrator role. */
    suspend fun deleteUser(username: String) {
        call(HttpMethod.Delete, "users/${username.encodeURLPathPart()}")
    }

    /** Changes a user's role, which applies from their next login. Needs the administrator role. */
    suspend fun setUserRole(
        username: String,
        role: String,
    ) {
        val body = buildJsonObject { put("role", role) }
        call(HttpMethod.Put, "users/${username.encodeURLPathPart()}/role", body.toString())
    }

    /**
     * Sets a user's password. [oldPassword] is required by Frigate for non-administrators. Returns the new session
     * token when the server issued one (it does when users change their own password), or `null`.
     * A 401 carrying Frigate's JSON message means a wrong [oldPassword] and is not retried with a new login.
     */
    suspend fun setPassword(
        username: String,
        password: String,
        oldPassword: String?,
    ): String? {
        // Renews an expired token first, so that a 401 of the change itself is unlikely to be about the token.
        version()
        val body =
            buildJsonObject {
                put("password", password)
                if (oldPassword != null) put("old_password", oldPassword)
            }
        val response =
            authorized(
                HttpMethod.Put,
                "users/${username.encodeURLPathPart()}/password",
                body.toString(),
                isAnswer = { status, answer ->
                    status == HttpStatusCode.Unauthorized && jsonString(answer, "message") != null
                },
            )
        return tokenCookie(response)
    }

    /** Writes part of the configuration file and applies it at once (`requires_restart: 0`). */
    suspend fun setConfig(
        configData: JsonObject,
        updateTopic: String? = null,
    ): ResultDto {
        val body =
            buildJsonObject {
                put("requires_restart", 0)
                put("config_data", configData)
                if (updateTopic != null) put("update_topic", updateTopic)
            }
        return decode(call(HttpMethod.Put, "config/set", body.toString()))
    }

    /** Restarts Frigate. Needs the administrator role. The server may stop before it answers. */
    suspend fun restart() {
        call(HttpMethod.Post, "restart", "{}")
    }

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

    /** The space the recordings of each camera take. Needs the administrator role. */
    suspend fun recordingStorage(): Map<String, CameraStorageDto> = decode(get("recordings/storage"))

    /** The exports kept on the server, newest first. */
    suspend fun exports(): List<ExportDto> = decode(get("exports"))

    /** Removes exports and their files from the server. Needs the administrator role. */
    suspend fun deleteExports(ids: List<String>) {
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }
        call(HttpMethod.Post, "exports/delete", body.toString())
    }

    /**
     * Marks a tracked object as one to keep, or no longer. The server keeps its entry and picture, and its video
     * when it frees space; recordings still expire. Needs the administrator role.
     */
    suspend fun setRetained(
        eventId: String,
        retained: Boolean,
    ) {
        val method = if (retained) HttpMethod.Post else HttpMethod.Delete
        call(method, "events/${eventId.encodeURLPathPart()}/retain", "{}")
    }

    suspend fun frigateProfiles(): FrigateProfilesDto = decode(get("profiles"))

    /** The most recent picture of [camera] as a JPEG [height] pixels high. */
    suspend fun latestFrame(
        camera: String,
        height: Int,
    ): ByteArray = bytes("api/${camera.encodeURLPathPart()}/latest.jpg?height=$height&quality=$PREVIEW_QUALITY")

    /** The recorded picture of [camera] at [time], in seconds since the epoch, as a JPEG [height] pixels high. */
    suspend fun recordingStill(
        camera: String,
        time: Long,
        height: Int,
    ): ByteArray = bytes("api/${camera.encodeURLPathPart()}/recordings/$time/snapshot.jpg?height=$height")

    private suspend fun bytes(path: String): ByteArray {
        val response = authorized(HttpMethod.Get, path, root = "")
        return transport { response.readRawBytes() }
    }

    /** Recorded segments of [camera] between two times in seconds since the epoch. */
    suspend fun recordings(
        camera: String,
        after: Long,
        before: Long,
    ): List<RecordingDto> = decode(get("${camera.encodeURLPathPart()}/recordings?after=$after&before=$before"))

    /** The days with recordings of [camera], in the time zone [zoneId]. */
    suspend fun recordingDays(
        camera: String,
        zoneId: String,
    ): List<RecordingDayDto> =
        decode(get("${camera.encodeURLPathPart()}/recordings/summary?timezone=${zoneId.encodeURLParameter()}"))

    /** Review moments between two times, newest first. [cameras] and [labels] are `null` for all of them. */
    suspend fun reviews(
        after: Long,
        before: Long,
        cameras: List<String>? = null,
        labels: List<String>? = null,
        severity: String? = null,
        limit: Int? = null,
    ): List<ReviewDto> {
        val query =
            listOfNotNull(
                "after=$after",
                "before=$before",
                cameras?.let { "cameras=${it.joinToString(",").encodeURLParameter()}" },
                labels?.let { "labels=${it.joinToString(",").encodeURLParameter()}" },
                severity?.let { "severity=$it" },
                limit?.let { "limit=$it" },
            ).joinToString("&")
        return decode(get("review?$query"))
    }

    /** One review moment. The answer does not say whether it has been viewed. */
    suspend fun review(id: String): ReviewDto = decode(get("review/${id.encodeURLPathPart()}"))

    /**
     * Removes review moments **and deletes the recordings of their cameras in those intervals**, for good. Needs the
     * administrator role.
     */
    suspend fun deleteReviews(ids: List<String>) {
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }
        call(HttpMethod.Post, "reviews/delete", body.toString())
    }

    suspend fun markReviewed(ids: List<String>) {
        val body =
            buildJsonObject {
                putJsonArray("ids") { ids.forEach { add(it) } }
                put("reviewed", true)
            }
        call(HttpMethod.Post, "reviews/viewed", body.toString())
    }

    /**
     * Starts recording [camera] on demand, as the web interface does: an event that stays open until [endEvent].
     * Needs the administrator role, and recording turned on. An event with a set duration could not be ended.
     */
    suspend fun startManualEvent(camera: String): CreatedEventDto {
        val body =
            buildJsonObject {
                put("include_recording", true)
                put("duration", JsonNull)
            }
        val path = "events/${camera.encodeURLPathPart()}/on_demand/create"
        return decode(call(HttpMethod.Post, path, body.toString()))
    }

    suspend fun endEvent(id: String) {
        // The body is required, even with nothing in it.
        call(HttpMethod.Put, "events/${id.encodeURLPathPart()}/end", "{}")
    }

    suspend fun event(id: String): EventDto = decode(get("events/${id.encodeURLPathPart()}"))

    /** Asks the server to cut a video file of an interval. It is made in the background. */
    suspend fun startExport(
        camera: String,
        start: Long,
        end: Long,
        name: String? = null,
    ): StartedExportDto {
        val body = buildJsonObject { if (name != null) put("name", name) }
        val path = "export/${camera.encodeURLPathPart()}/start/$start/end/$end"
        return decode(call(HttpMethod.Post, path, body.toString()))
    }

    suspend fun export(id: String): ExportDto = decode(get("exports/${id.encodeURLPathPart()}"))

    /**
     * The address of a file the server stores, such as a thumbnail or an export, given its path on the server
     * (`/media/frigate/clips/…`). Such files are served outside `/api` and need [authorizationHeaders].
     */
    fun mediaUrl(serverPath: String): String =
        "${baseUrl.trimEnd('/')}/${serverPath.removePrefix(MEDIA_ROOT).trimStart('/')}"

    /**
     * Reads a file the server stores piece by piece into [receive], without keeping it in memory: videos are large.
     * Each call to [receive] gets a buffer and how much of it is filled. Cancelling stops the download.
     */
    suspend fun download(
        serverPath: String,
        receive: suspend (bytes: ByteArray, count: Int) -> Unit,
    ) {
        val usedToken = authenticator.token()
        if (stream(serverPath, usedToken, receive) == null) return
        // Refused before anything was received: once more with a new token.
        val freshToken = authenticator.renew(usedToken, passwordLogin)
        val refusal = stream(serverPath, freshToken, receive) ?: return
        throw stillRefused(refusal)
    }

    /** The status of a refused token, or `null` once the whole file has been received. */
    private suspend fun stream(
        serverPath: String,
        token: String?,
        receive: suspend (bytes: ByteArray, count: Int) -> Unit,
    ): HttpStatusCode? {
        // A failure of the receiver, such as a full disk, says nothing about the server.
        var receiving: Throwable? = null
        return try {
            http
                .prepareGet(mediaUrl(serverPath)) {
                    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                    // As long as it takes: only a silence of the server ends it.
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                }.execute { response ->
                    if (isSignInRefusal(response)) return@execute response.status
                    if (!response.status.isSuccess()) {
                        throw FrigateError.UnexpectedResponse("HTTP ${response.status.value} on the file")
                    }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    var received = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count == -1) break
                        if (count == 0) continue
                        received += count
                        try {
                            receive(buffer, count)
                        } catch (failure: Throwable) {
                            receiving = failure
                            throw failure
                        }
                    }
                    // A connection that drops early must not pass for the end of the file.
                    val expected = response.contentLength()
                    if (expected != null && received < expected) throw FrigateError.Unreachable()
                    null
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw receiving
                ?: failure as? FrigateError
                ?: findCertificateRejection(failure)?.let { FrigateError.UntrustedCertificate(it.sha256Fingerprint) }
                ?: FrigateError.Unreachable(failure)
        }
    }

    /** The bytes of a file the server stores. */
    suspend fun mediaFile(serverPath: String): ByteArray = bytes(serverPath.removePrefix(MEDIA_ROOT).trimStart('/'))

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

    /** Headers for a player that downloads media by itself. Signs in again first if the token has expired. */
    suspend fun authorizationHeaders(): Map<String, String> {
        version()
        return currentHeaders()
    }

    /**
     * The headers to send right now, without asking Frigate: a token about to end is replaced first, where the
     * authenticator can tell. Players ask before each request, as a video outlasts a short-lived token.
     */
    suspend fun currentHeaders(): Map<String, String> =
        authenticator.token()?.let { mapOf(HttpHeaders.Authorization to "Bearer $it") }.orEmpty()

    /** Allows a new login attempt after a failed one. Call it when the user asks to retry. */
    suspend fun resetLoginFailure() = authenticator.reset()

    private suspend fun get(path: String): String = call(HttpMethod.Get, path)

    private suspend fun call(
        method: HttpMethod,
        path: String,
        jsonBody: String? = null,
    ): String {
        val response = authorized(method, path, jsonBody)
        return transport { response.bodyAsText() }
    }

    /**
     * A successful answer, after logging in again if the token was refused. [isAnswer] tells a refusal that is
     * about the request itself, which a new login would not change: it is reported without trying again.
     */
    private suspend fun authorized(
        method: HttpMethod,
        path: String,
        jsonBody: String? = null,
        root: String = API_ROOT,
        isAnswer: (status: HttpStatusCode, body: String) -> Boolean = { _, _ -> false },
    ): HttpResponse {
        val usedToken = authenticator.token()
        var response = send(method, path, usedToken, jsonBody, root)
        if (isSignInRefusal(response)) {
            if (response.status == HttpStatusCode.Unauthorized) {
                val refusal = transport { response.bodyAsText() }
                if (isAnswer(response.status, refusal)) throw refused(response.status, "/$root$path", refusal)
            }
            val freshToken = authenticator.renew(usedToken, passwordLogin)
            response = send(method, path, freshToken, jsonBody, root)
            if (isSignInRefusal(response)) {
                if (response.status == HttpStatusCode.Unauthorized) {
                    val again = transport { response.bodyAsText() }
                    if (isAnswer(response.status, again)) throw refused(response.status, "/$root$path", again)
                }
                throw stillRefused(response.status)
            }
        }
        if (!response.status.isSuccess()) {
            throw refused(response.status, "/$root$path", transport { response.bodyAsText() })
        }
        return response
    }

    /**
     * Whether [response] refuses who is asking, so that another token is worth a try. Frigate answers 401. A proxy
     * that signs users in answers 401 too, sends to its login page, or answers 403 with a page of its own; a 403 of
     * Frigate is JSON and means the user's role does not allow it.
     *
     * A server set up for Frigate's login that sends to a login page has something else in front of it, which no
     * password can open: that is reported at once.
     */
    private suspend fun isSignInRefusal(response: HttpResponse): Boolean {
        val loginPage = externalLoginPage(response)
        if (loginPage != null) {
            if (!authenticator.isExternal) throw FrigateError.ExternalSignIn(loginPage)
            return true
        }
        if (response.status == HttpStatusCode.Unauthorized) return true
        val isProxyPage =
            response.status == HttpStatusCode.Forbidden &&
                response.contentType()?.match(ContentType.Text.Html) == true
        if (!isProxyPage) return false
        if (authenticator.isExternal) return true
        // oauth2-proxy may show its sign-in page instead of redirecting to the provider.
        if (PROXY_PATH in transport { response.bodyAsText() }) throw FrigateError.ExternalSignIn("")
        return false
    }

    // A redirect to another host, to the proxy's own sign-in path, or to any provider's authorization page, which
    // may share the server's host.
    private fun externalLoginPage(response: HttpResponse): String? {
        if (response.status.value !in REDIRECTS) return null
        val location = response.headers[HttpHeaders.Location] ?: return null
        val isAbsolute = location.startsWith("http://") || location.startsWith("https://")
        val leaves = isAbsolute && Url(location).host != Url(baseUrl).host
        val query = location.substringAfter('?', "")
        val asksToAuthorize = "client_id=" in query && "response_type=" in query
        return location.takeIf { leaves || asksToAuthorize || PROXY_PATH in location.substringBefore('?') }
    }

    private fun stillRefused(status: HttpStatusCode): FrigateError =
        if (authenticator.isExternal) {
            FrigateError.SignInRefused(status.value)
        } else {
            FrigateError.LoginRejected()
        }

    private fun refused(
        status: HttpStatusCode,
        path: String,
        body: String,
    ): FrigateError.UnexpectedResponse {
        // The server's explanation helps, e.g. go2rtc saying that no codec matches.
        val reason = body.trim().take(MAX_ERROR_LENGTH)
        val endpoint = path.substringBefore('?')
        return FrigateError.UnexpectedResponse(
            "HTTP ${status.value} on $endpoint $reason".trim(),
            status = status.value,
            serverMessage = jsonString(body, "message") ?: jsonString(body, "detail"),
        )
    }

    /** The text of the field [name], if [body] is a JSON object that has it. */
    private fun jsonString(
        body: String,
        name: String,
    ): String? {
        val json =
            try {
                frigateJson.parseToJsonElement(body) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
        return (json?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Returns the new token, or `null` if the server has authentication turned off. */
    private suspend fun login(stored: Credentials): String? {
        val body =
            buildJsonObject {
                put("user", stored.username)
                put("password", stored.password)
            }
        val response = send(HttpMethod.Post, "login", token = null, jsonBody = body.toString())
        // Throws if a proxy answers in Frigate's place.
        if (response.status != HttpStatusCode.Unauthorized) isSignInRefusal(response)
        return when {
            response.status == HttpStatusCode.Unauthorized -> throw FrigateError.LoginRejected()
            response.status == HttpStatusCode.TooManyRequests -> throw FrigateError.RateLimited()
            response.status == HttpStatusCode.NotFound -> null
            !response.status.isSuccess() ->
                throw FrigateError.UnexpectedResponse("HTTP ${response.status.value} on /api/login")
            else -> sessionToken(response)
        }
    }

    private fun sessionToken(response: HttpResponse): String =
        tokenCookie(response)
            ?: throw FrigateError.UnexpectedResponse("login succeeded but no session cookie was returned")

    // The cookie name is configurable on the server, so the token is recognised by its JWT shape.
    private fun tokenCookie(response: HttpResponse): String? =
        response.setCookie().firstOrNull { cookie -> cookie.value.count { it == '.' } == 2 }?.value

    private suspend fun send(
        method: HttpMethod,
        path: String,
        token: String?,
        jsonBody: String? = null,
        root: String = API_ROOT,
    ): HttpResponse =
        transport {
            http.request("${baseUrl.trimEnd('/')}/$root$path") {
                this.method = method
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (method != HttpMethod.Get) header("X-CSRF-TOKEN", "1")
                // The proxy caches answers for a few seconds, which would hide a setting just changed.
                if (method == HttpMethod.Get && path.substringBefore('?') in UNCACHED) header("X-Cache-Bypass", "1")
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
