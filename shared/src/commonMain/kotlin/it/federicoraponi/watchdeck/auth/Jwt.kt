package it.federicoraponi.watchdeck.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/**
 * The claims inside [token], or `null` if it is not a JWT. The signature is not checked: the app only reads what a
 * provider sent it directly over HTTPS, and the server that receives the token checks it.
 */
internal fun jwtClaims(token: String): JsonObject? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    return try {
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        Json.parseToJsonElement(decoder.decode(parts[1]).decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SerializationException) {
        null
    }
}

internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

/** `aud` is one name or a list of them. */
internal fun JsonObject.audiences(): List<String> =
    when (val audience = get("aud")) {
        is JsonArray -> audience.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(audience.content)
        else -> emptyList()
    }
