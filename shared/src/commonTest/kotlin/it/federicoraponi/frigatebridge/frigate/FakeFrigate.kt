package it.federicoraponi.frigatebridge.frigate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay

/** An in-memory Frigate server that behaves like 0.18 for login and the endpoints the app reads. */
class FakeFrigate(
    var username: String = "viewer",
    var password: String = "correct-password",
    var version: String = "0.18.3-abc1234",
    var config: String = SAMPLE_CONFIG,
    var allowedCameras: List<String> = listOf("front_door", "garden", "garage"),
    var authEnabled: Boolean = true,
    var cookieName: String = "frigate_token",
) {
    var recordings = """[{"start_time": 1000.2, "end_time": 1010.2}, {"start_time": 1010.2, "end_time": 1200.7}]"""
    var reviews = "[]"
    var exports = "[]"
    var online = true
    var loginStatus: HttpStatusCode? = null
    var responseDelayMillis = 0L
    var loginCount = 0
        private set
    val requests = mutableListOf<HttpRequestData>()
    var lastLoginBody = ""
        private set

    private var validToken: String? = null

    /** Makes every issued token invalid, as when it expires after 24 hours. */
    fun expireSession() {
        validToken = null
    }

    fun client(): HttpClient = HttpClient(MockEngine { request -> handle(request) })

    suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        delay(responseDelayMillis)
        if (!online) throw RuntimeException("connection refused")
        requests += request
        val path = request.url.encodedPath.substringAfter("/api/")
        if (path == "login" && request.method == HttpMethod.Post) return login(request)
        val authorized =
            !authEnabled ||
                (validToken != null && request.headers[HttpHeaders.Authorization] == "Bearer $validToken")
        if (!authorized) return respond("", HttpStatusCode.Unauthorized)
        return when (path) {
            "version" -> respond(version)
            "profile" -> {
                val cameras = allowedCameras.joinToString(",") { "\"$it\"" }
                respond("""{"username":"$username","role":"admin","allowed_cameras":[$cameras]}""")
            }
            "config" -> respond(config)
            "stats" -> respond("""{"service":{"uptime":120,"version":"$version"},"cameras":{}}""")
            "recordings/storage" -> respond("""{"front_door":{"usage":2048.0,"usage_percent":4.0}}""")
            "exports" -> respond(exports)
            "/exports/clip.mp4" -> respond(EXPORTED)
            "exports/delete" -> respond("""{"success":true,"message":"Deleted"}""")
            "profiles" -> respond("""{"profiles":[],"active_profile":null,"last_activated":{}}""")
            "go2rtc/webrtc" -> respond("""{"type":"answer","sdp":"answer-for-${request.url.parameters["src"]}"}""")
            "front_door/recordings" -> respond(recordings)
            "front_door/recordings/summary" -> respond("""[{"day": "1970-01-01", "hours": []}]""")
            "review" -> respond(reviews)
            "events/front_door/on_demand/create" ->
                respond("""{"success":true,"message":"Created","event_id":"manual"}""")
            "events/manual/end" -> respond("""{"success":true,"message":"Ended"}""")
            "events/manual" -> respond("""{"id":"manual","start_time":995.5,"end_time":1020.2}""")
            "export/front_door/start/995/end/1021" -> respond("""{"success":true,"export_id":"clip"}""")
            "exports/clip" ->
                respond("""{"id":"clip","video_path":"/media/frigate/exports/clip.mp4","in_progress":false}""")
            "review/moment" -> respond(MOMENT)
            "reviews/viewed" -> respond("""{"success":true,"message":"Marked"}""")
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private suspend fun MockRequestHandleScope.login(request: HttpRequestData): HttpResponseData {
        loginCount++
        lastLoginBody = request.body.toByteArray().decodeToString()
        loginStatus?.let { return respond("", it) }
        if (!authEnabled) return respond("""{"message":"Authentication is disabled"}""", HttpStatusCode.NotFound)
        val expected = """{"user":"$username","password":"$password"}"""
        if (lastLoginBody != expected) return respond("""{"message":"Login failed"}""", HttpStatusCode.Unauthorized)
        val token = "header.payload$loginCount.signature"
        validToken = token
        return respond(
            "",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.SetCookie, "$cookieName=$token; HttpOnly; Max-Age=86400; Path=/; SameSite=lax"),
        )
    }

    companion object {
        const val EXPORTED = "not really a video"

        /** A review moment of the front door, as `/api/review/{id}` describes it. */
        val MOMENT =
            """
            {
              "id": "moment",
              "camera": "front_door",
              "start_time": 2000.4,
              "end_time": 2025.6,
              "severity": "alert",
              "thumb_path": "/media/frigate/clips/review/moment.webp",
              "data": {"objects": ["person"], "zones": ["porch"]}
            }
            """.trimIndent()

        val SAMPLE_CONFIG =
            """
            {
              "mqtt": {"enabled": false},
              "cameras": {
                "garage": {"enabled": false, "ui": {"order": 2, "dashboard": true}},
                "front_door": {"friendly_name": "Front door", "enabled": true, "ui": {"order": 1}},
                "garden": {"friendly_name": null, "ui": {"order": 1, "dashboard": false}}
              },
              "camera_groups": {
                "Outside": {"cameras": "garden,front_door", "icon": "tree", "order": 2},
                "Indoor": {"cameras": ["garage"], "order": 1}
              }
            }
            """.trimIndent()
    }
}

class InMemoryTokenStore(
    var token: String? = null,
) : TokenStore {
    override suspend fun read(): String? = token

    override suspend fun write(token: String?) {
        this.token = token
    }
}
