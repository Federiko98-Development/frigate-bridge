package it.federicoraponi.frigatebridge.frigate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An in-memory Frigate server that behaves like 0.18 for login, the endpoints the app reads, and the
 * administration of users and configuration.
 */
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

    /** The role of each user, by name. The signed-in account is an administrator unless a test says otherwise. */
    val users = mutableMapOf("admin" to "admin", username to "admin")

    /** The passwords of the users other than the signed-in one, whose password is [password]. */
    val passwords = mutableMapOf<String, String>()

    /** The bodies of the changes of the configuration, as received. */
    val configSetBodies = mutableListOf<String>()

    /** Answers changes of the configuration with success without making them, as with a read-only file. */
    var ignoreConfigWrites = false

    /** The ids sent to be removed, with their recordings or as exports. */
    val deletedReviews = mutableListOf<String>()
    val deletedExports = mutableListOf<String>()

    /** The switches of cameras, as `feature=body`, in the order received. */
    val cameraChanges = mutableListOf<String>()

    private var validToken: String? = null
    private var issuedTokens = 0

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
        val segments = path.split('/').map { it.decodeURLPart() }
        if (segments.first() == "users") return userRequest(request, segments.drop(1))
        if (path == "config/set" && request.method == HttpMethod.Put) return asAdmin { setConfig(request) }
        if (path == "restart" && request.method == HttpMethod.Post) {
            return asAdmin { json("""{"success":true,"message":"Restarting"}""") }
        }
        if (path == "reviews/delete") {
            return asAdmin {
                deletedReviews += request.body.toByteArray().decodeToString()
                json("""{"success":true,"message":"Deleted review items."}""")
            }
        }
        if (path.startsWith("camera/") && "/set/" in path) {
            cameraChanges += path.substringAfter("/set/") + "=" + request.body.toByteArray().decodeToString()
            return json("""{"success":true,"message":"Set"}""")
        }
        // The moment's own interval depends on when it happened.
        if (path.startsWith("export/front_door/start/199")) return respond("""{"success":true,"export_id":"clip"}""")
        return when (path) {
            "version" -> respond(version)
            "profile" -> {
                val cameras = allowedCameras.joinToString(",") { "\"$it\"" }
                val role = users[username] ?: "viewer"
                respond("""{"username":"$username","role":"$role","allowed_cameras":[$cameras]}""")
            }
            "config" -> respond(servedConfig())
            "stats" -> respond("""{"service":{"uptime":120,"version":"$version"},"cameras":{}}""")
            "recordings/storage" -> respond("""{"front_door":{"usage":2048.0,"usage_percent":4.0}}""")
            "exports" -> respond(exports)
            "/exports/clip.mp4" -> respond(EXPORTED)
            "exports/delete" -> {
                deletedExports += request.body.toByteArray().decodeToString()
                respond("""{"success":true,"message":"Deleted"}""")
            }
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
        return respond("", HttpStatusCode.OK, sessionCookie("header.payload$loginCount.signature"))
    }

    /** Makes [token] the valid one and returns the header that gives it to the client. */
    private fun sessionCookie(token: String): Headers {
        validToken = token
        return headersOf(
            HttpHeaders.SetCookie,
            "$cookieName=$token; HttpOnly; Max-Age=86400; Path=/; SameSite=lax",
        )
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.refuse(
        status: HttpStatusCode,
        message: String,
        field: String = "message",
    ): HttpResponseData = json(buildJsonObject { put(field, message) }.toString(), status)

    private suspend fun MockRequestHandleScope.asAdmin(
        answer: suspend MockRequestHandleScope.() -> HttpResponseData,
    ): HttpResponseData {
        val role = users[username] ?: "viewer"
        return if (role == "admin") {
            answer()
        } else {
            refuse(HttpStatusCode.Forbidden, "Role $role not authorized. Required: admin", field = "detail")
        }
    }

    private suspend fun MockRequestHandleScope.userRequest(
        request: HttpRequestData,
        segments: List<String>,
    ): HttpResponseData {
        val name = segments.getOrNull(0)
        val action = segments.getOrNull(1)
        return when {
            name == null && request.method == HttpMethod.Get -> asAdmin { listUsers() }
            name == null && request.method == HttpMethod.Post -> asAdmin { createUser(request) }
            name != null && action == null && request.method == HttpMethod.Delete -> asAdmin { deleteUser(name) }
            name != null && action == "role" && request.method == HttpMethod.Put -> asAdmin { setRole(name, request) }
            name != null && action == "password" && request.method == HttpMethod.Put -> setPassword(name, request)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.listUsers(): HttpResponseData {
        val list =
            buildJsonArray {
                users.entries.sortedBy { it.key }.forEach { (name, role) ->
                    add(
                        buildJsonObject {
                            put("username", name)
                            put("role", role)
                        },
                    )
                }
            }
        return json(list.toString())
    }

    private suspend fun MockRequestHandleScope.createUser(request: HttpRequestData): HttpResponseData {
        val body = request.jsonBody()
        val name = body.text("username").orEmpty()
        val role = body.text("role") ?: "viewer"
        val newPassword = body.text("password").orEmpty()
        if (!USERNAME.matches(name) || name.all { it == '.' }) {
            return refuse(HttpStatusCode.BadRequest, "Invalid username")
        }
        if (role !in roleNames()) return badRole()
        passwordWeakness(newPassword)?.let { return refuse(HttpStatusCode.BadRequest, it) }
        if (name in users) return respond("Internal Server Error", HttpStatusCode.InternalServerError)
        users[name] = role
        passwords[name] = newPassword
        return json(buildJsonObject { put("username", name) }.toString())
    }

    private fun MockRequestHandleScope.deleteUser(name: String): HttpResponseData {
        if (name == "admin") return refuse(HttpStatusCode.Forbidden, "Cannot delete admin user")
        users.remove(name)
        passwords.remove(name)
        return json("""{"success":true}""")
    }

    private suspend fun MockRequestHandleScope.setRole(
        name: String,
        request: HttpRequestData,
    ): HttpResponseData {
        if (name == "admin") return refuse(HttpStatusCode.Forbidden, "Cannot modify admin user's role")
        val role = request.jsonBody().text("role").orEmpty()
        if (role !in roleNames()) return badRole()
        if (name in users) users[name] = role
        return json("""{"success":true}""")
    }

    private suspend fun MockRequestHandleScope.setPassword(
        name: String,
        request: HttpRequestData,
    ): HttpResponseData {
        val body = request.jsonBody()
        val isAdmin = users[username] == "admin"
        if (!isAdmin && name != username) {
            return refuse(HttpStatusCode.Forbidden, "Users can only update their own password", field = "detail")
        }
        val newPassword = body.text("password").orEmpty()
        passwordWeakness(newPassword)?.let { return refuse(HttpStatusCode.BadRequest, it) }
        if (name !in users) return refuse(HttpStatusCode.NotFound, "User not found")
        if (!isAdmin) {
            val old = body.text("old_password")
            if (old.isNullOrEmpty()) return refuse(HttpStatusCode.BadRequest, "Current password is required")
            // A user who is not an administrator only gets this far for their own password.
            if (old != password) return refuse(HttpStatusCode.Unauthorized, "Current password is incorrect")
        }
        if (name != username) {
            passwords[name] = newPassword
            return json("""{"success":true}""")
        }
        password = newPassword
        issuedTokens++
        return respond("""{"success":true}""", HttpStatusCode.OK, sessionCookie("header.own$issuedTokens.signature"))
    }

    private fun passwordWeakness(candidate: String): String? =
        when {
            candidate.isEmpty() -> "Password cannot be empty"
            candidate.length < MIN_PASSWORD -> "Password must be at least $MIN_PASSWORD characters long"
            else -> null
        }

    private fun roleNames(): Set<String> {
        val auth = Json.parseToJsonElement(config).jsonObject["auth"] as? JsonObject
        val roles = auth?.get("roles") as? JsonObject
        return setOf("admin", "viewer") + roles?.keys.orEmpty()
    }

    private fun MockRequestHandleScope.badRole(): HttpResponseData =
        refuse(HttpStatusCode.BadRequest, "Role must be one of: ${roleNames().joinToString(", ")}")

    /** The configuration as `/api/config` reports it, which says whether authentication is on. */
    private fun servedConfig(): String {
        if (authEnabled) return config
        // A test may serve something that is not a configuration at all: it stays as it is.
        val root =
            try {
                Json.parseToJsonElement(config) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return config
        val auth = root["auth"] as? JsonObject ?: JsonObject(emptyMap())
        if ("enabled" in auth) return config
        return JsonObject(root + ("auth" to JsonObject(auth + ("enabled" to JsonPrimitive(false))))).toString()
    }

    private suspend fun MockRequestHandleScope.setConfig(request: HttpRequestData): HttpResponseData {
        val text = request.body.toByteArray().decodeToString()
        configSetBodies += text
        if (!ignoreConfigWrites) {
            val changes =
                Json
                    .parseToJsonElement(text)
                    .jsonObject
                    .getValue("config_data")
                    .jsonObject
            val changed =
                merge(Json.parseToJsonElement(config).jsonObject, changes)
                    ?: return json(
                        """{"success":false,"message":"Error updating config"}""",
                        HttpStatusCode.InternalServerError,
                    )
            config = changed.toString()
        }
        return json("""{"success":true,"message":"Config successfully updated"}""")
    }

    /**
     * Applies changes as Frigate does: objects are merged, lists and values replace, and an empty text or `null`
     * deletes a key. Returns `null` when a key to delete is not there.
     */
    private fun merge(
        target: JsonObject,
        changes: JsonObject,
    ): JsonObject? {
        val result = LinkedHashMap<String, JsonElement>(target)
        for ((key, value) in changes) {
            when {
                value.isDeletion() -> if (result.remove(key) == null) return null
                value is JsonObject ->
                    result[key] = merge(result[key] as? JsonObject ?: JsonObject(emptyMap()), value) ?: return null
                else -> result[key] = value
            }
        }
        return JsonObject(result)
    }

    private fun JsonElement.isDeletion() = this is JsonNull || (this is JsonPrimitive && isString && content.isEmpty())

    private suspend fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeUnless { it is JsonNull || it is JsonArray }?.jsonPrimitive?.content

    companion object {
        private const val MIN_PASSWORD = 12
        private val USERNAME = Regex("[A-Za-z0-9._]+")

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
