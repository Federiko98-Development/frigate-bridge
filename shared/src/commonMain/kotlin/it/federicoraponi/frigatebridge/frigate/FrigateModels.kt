package it.federicoraponi.frigatebridge.frigate

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    val live: CameraLiveDto = CameraLiveDto(),
    val record: FeatureDto = FeatureDto(),
    val detect: FeatureDto = FeatureDto(),
    val motion: FeatureDto = FeatureDto(),
    val snapshots: FeatureDto = FeatureDto(),
    val onvif: OnvifDto = OnvifDto(),
)

@Serializable
data class OnvifDto(
    /** Set when the camera can be controlled through ONVIF, e.g. to move it. */
    val host: String = "",
)

/** What a camera that can be moved supports: `pt` (pan and tilt), `zoom`, `focus`, … */
@Serializable
data class PtzInfoDto(
    val features: List<String> = emptyList(),
    val presets: List<String> = emptyList(),
)

/** A function of a camera that can be turned on and off. */
@Serializable
data class FeatureDto(
    val enabled: Boolean = false,
    /** Frigate only turns recording on at run time if the configuration file enables it. */
    @SerialName("enabled_in_config") val enabledInConfig: Boolean? = null,
)

@Serializable
data class CameraLiveDto(
    /** Label shown to the user to go2rtc stream name. */
    @Serializable(with = StreamMapSerializer::class)
    val streams: Map<String, String> = emptyMap(),
)

/** Frigate may send an empty list where it has no streams to map. */
object StreamMapSerializer : KSerializer<Map<String, String>> by EmptyListAsMapSerializer

private object EmptyListAsMapSerializer :
    JsonTransformingSerializer<Map<String, String>>(MapSerializer(String.serializer(), String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonObject(emptyMap()) else element
}

/** A recorded segment of a camera, with times in seconds since the epoch. */
@Serializable
data class RecordingDto(
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
)

/** A WebRTC offer or answer, as go2rtc exchanges them. */
@Serializable
data class SessionDescriptionDto(
    val type: String,
    val sdp: String,
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
