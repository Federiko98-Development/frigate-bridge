package it.federicoraponi.frigatebridge.server

import it.federicoraponi.frigatebridge.camera.CameraOption
import it.federicoraponi.frigatebridge.camera.EditableGroup
import it.federicoraponi.frigatebridge.camera.camerasInOrder
import it.federicoraponi.frigatebridge.camera.displayName
import it.federicoraponi.frigatebridge.frigate.ConfigDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The user the app is signed in as on a server. */
data class Account(
    val username: String,
    val role: String,
) {
    val isAdmin: Boolean get() = role == "admin"

    /** Frigate's internal port signs nobody in. */
    val isAnonymous: Boolean get() = username == "anonymous" || username.isBlank()
}

data class FrigateUser(
    val username: String,
    val role: String,
) {
    /** Created by Frigate at first start: it cannot be deleted and its role cannot change. */
    val isBuiltInAdmin: Boolean get() = username == "admin"
}

/** A role defined in the configuration, other than admin and viewer. */
data class CustomRole(
    val name: String,
    val cameras: List<String>,
)

/** What an administrator can change in a server's configuration, as the configuration holds it. */
data class AdminConfig(
    val authEnabled: Boolean,
    /** By name, without admin and viewer. */
    val customRoles: List<CustomRole>,
    /** The camera groups with all their members, in their order. */
    val groups: List<EditableGroup>,
    /** Every camera of the configuration, in the order of the camera list. */
    val cameras: List<CameraOption>,
    val birdseyeEnabled: Boolean,
)

/** The server accepted a change of its configuration, but the change is not there when read again. */
class AdminNotSaved : Exception("The server did not save the change")

enum class NameProblem { Missing, TooShort, TooLong, InvalidCharacters, NeedsLetter, Reserved, Taken }

enum class PasswordProblem { TooShort, Mismatch, CurrentMissing }

/** What Frigate accepts as users, roles and passwords, checked before anything is sent. */
object AccessRules {
    const val MIN_PASSWORD = 12
    const val MAX_USERNAME = 30
    const val MAX_ROLE = 20
    val BUILT_IN_ROLES = listOf("admin", "viewer")

    /**
     * ASCII letters, digits, dots and underscores, not only dots, at most [MAX_USERNAME] characters.
     * `anonymous` is reserved: Frigate treats that user name as nobody signed in. Frigate does not check for
     * duplicates, so [existing] must be the current users.
     */
    fun usernameProblem(
        name: String,
        existing: Collection<String>,
    ): NameProblem? =
        when {
            name.isBlank() -> NameProblem.Missing
            name.length > MAX_USERNAME -> NameProblem.TooLong
            !name.all { it.isAsciiLetterOrDigit() || it == '.' || it == '_' } -> NameProblem.InvalidCharacters
            name.all { it == '.' } -> NameProblem.InvalidCharacters
            name == "anonymous" -> NameProblem.Reserved
            name in existing -> NameProblem.Taken
            else -> null
        }

    /**
     * Stricter than Frigate: lowercase ASCII letters, digits and underscores (a proxy lowercases its role header,
     * so it could never select a role with capitals), with at least one letter, at most [MAX_ROLE] characters,
     * and not a built-in role.
     */
    fun roleNameProblem(
        name: String,
        existing: Collection<String>,
    ): NameProblem? =
        when {
            name.isBlank() -> NameProblem.Missing
            BUILT_IN_ROLES.any { it.equals(name, ignoreCase = true) } -> NameProblem.Reserved
            name.length > MAX_ROLE -> NameProblem.TooLong
            !name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } -> NameProblem.InvalidCharacters
            name.none { it in 'a'..'z' } -> NameProblem.NeedsLetter
            name in existing -> NameProblem.Taken
            else -> null
        }

    fun passwordProblem(
        password: String,
        confirmation: String,
    ): PasswordProblem? =
        when {
            password.length < MIN_PASSWORD -> PasswordProblem.TooShort
            password != confirmation -> PasswordProblem.Mismatch
            else -> null
        }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

internal fun adminConfig(config: ConfigDto) =
    AdminConfig(
        authEnabled = config.auth.enabled,
        customRoles =
            config.auth.roles
                .filterKeys { it !in AccessRules.BUILT_IN_ROLES }
                .map { (name, cameras) -> CustomRole(name, cameras) }
                .sortedBy { it.name },
        groups =
            config.cameraGroups
                .map { (name, group) -> EditableGroup(name, group.cameras, group.icon, group.order) }
                .sortedWith(compareBy({ it.order }, { it.name })),
        cameras = config.camerasInOrder().map { (name, camera) -> CameraOption(name, camera.displayName(name)) },
        birdseyeEnabled = config.birdseye.enabled,
    )

/** The topic on which Frigate announces a change of the roles. */
internal const val ROLES_TOPIC = "config/auth"

// Only the role being changed is written: Frigate refuses to have admin and viewer written back.
internal fun roleChange(
    name: String,
    cameras: List<String>,
): JsonObject =
    buildJsonObject {
        putJsonObject("auth") {
            putJsonObject("roles") {
                putJsonArray(name) { cameras.forEach { add(it) } }
            }
        }
    }

/** An empty text deletes a key of the configuration. */
internal fun roleRemoval(name: String): JsonObject =
    buildJsonObject {
        putJsonObject("auth") {
            putJsonObject("roles") { put(name, "") }
        }
    }
