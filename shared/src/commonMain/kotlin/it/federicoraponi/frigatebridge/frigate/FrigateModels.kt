package it.federicoraponi.frigatebridge.frigate

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

// Only the fields the app uses. `/api/config` is a large, version-dependent document,
// so unknown fields are ignored and everything optional has a default.

@Serializable
data class ConfigDto(
    val cameras: Map<String, CameraConfigDto> = emptyMap(),
    @SerialName("camera_groups") val cameraGroups: Map<String, CameraGroupDto> = emptyMap(),
)

@Serializable
data class CameraConfigDto(
    @SerialName("friendly_name") val friendlyName: String? = null,
    val enabled: Boolean = true,
    val ui: CameraUiDto = CameraUiDto(),
)

@Serializable
data class CameraUiDto(
    val order: Int = 0,
    val dashboard: Boolean = true,
)

@Serializable
data class CameraGroupDto(
    @Serializable(with = CameraListSerializer::class)
    val cameras: List<String> = emptyList(),
    val icon: String = "generic",
    val order: Int = 0,
)

/** Frigate sends a group's cameras either as a list or as a comma-separated string. */
object CameraListSerializer : KSerializer<List<String>> by CommaSeparatedListSerializer

private object CommaSeparatedListSerializer :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive) return element
        val names =
            element.content
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return JsonArray(names.map { JsonPrimitive(it) })
    }
}

@Serializable
data class UserProfileDto(
    val username: String = "",
    val role: String = "viewer",
    @SerialName("allowed_cameras") val allowedCameras: List<String>? = null,
)

@Serializable
data class StatsDto(
    val service: ServiceStatsDto = ServiceStatsDto(),
)

@Serializable
data class ServiceStatsDto(
    val uptime: Long = 0,
    val version: String = "",
)

/** Frigate's configuration profiles (e.g. "away"). */
@Serializable
data class FrigateProfilesDto(
    @SerialName("active_profile") val activeProfile: String? = null,
)
