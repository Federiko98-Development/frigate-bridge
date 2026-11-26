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
    val duration: Double? = null,
)

/** A day of the recordings summary, as `yyyy-mm-dd` in the requested time zone. */
@Serializable
data class RecordingDayDto(
    val day: String,
)

@Serializable
data class ReviewDto(
    val id: String,
    val camera: String,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double? = null,
    val severity: String = "detection",
    @SerialName("thumb_path") val thumbPath: String = "",
    @Serializable(with = FlexibleBooleanSerializer::class)
    @SerialName("has_been_reviewed") val hasBeenReviewed: Boolean = false,
    val data: ReviewDataDto = ReviewDataDto(),
)

@Serializable
data class ReviewDataDto(
    /** The identifiers of the tracked objects that make up the moment. */
    val detections: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val audio: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
)

/** Frigate's answer to a request to start recording a moment. */
@Serializable
data class CreatedEventDto(
    val success: Boolean = true,
    val message: String = "",
    @SerialName("event_id") val eventId: String? = null,
)

/** A tracked or manually recorded event, with times in seconds since the epoch. */
@Serializable
data class EventDto(
    val id: String,
    @SerialName("start_time") val startTime: Double,
    /** `null` while the event is still going on. */
    @SerialName("end_time") val endTime: Double? = null,
)

/** Frigate's answer to a request for an export. */
@Serializable
data class StartedExportDto(
    val success: Boolean = true,
    val message: String = "",
    @SerialName("export_id") val exportId: String? = null,
)

@Serializable
data class ExportDto(
    val id: String,
    val camera: String = "",
    val name: String = "",
    /** When the export was asked for, in seconds since the epoch. */
    val date: Double = 0.0,
    @SerialName("thumb_path") val thumbPath: String = "",
    @SerialName("video_path") val videoPath: String = "",
    @SerialName("in_progress") val inProgress: Boolean = false,
)

/** The database may answer 0 or 1 where a boolean is expected. */
object FlexibleBooleanSerializer : KSerializer<Boolean> by ZeroOrOneSerializer

private object ZeroOrOneSerializer : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        when ((element as? JsonPrimitive)?.content) {
            "1" -> JsonPrimitive(true)
            "0" -> JsonPrimitive(false)
            else -> element
        }
}

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
    val cameras: Map<String, CameraStatsDto> = emptyMap(),
    val detectors: Map<String, DetectorStatsDto> = emptyMap(),
    /** By process; `frigate.full_system` is the whole machine. */
    @SerialName("cpu_usages") val cpuUsages: Map<String, ProcessUsageDto> = emptyMap(),
)

@Serializable
data class ServiceStatsDto(
    val uptime: Long = 0,
    val version: String = "",
    @SerialName("latest_version") val latestVersion: String = "",
    /** By path on the server, in mebibytes. */
    val storage: Map<String, DiskStatsDto> = emptyMap(),
)

@Serializable
data class DiskStatsDto(
    val total: Double = 0.0,
    val used: Double = 0.0,
    val free: Double = 0.0,
)

@Serializable
data class CameraStatsDto(
    @SerialName("camera_fps") val cameraFps: Double = 0.0,
    @SerialName("skipped_fps") val skippedFps: Double = 0.0,
)

@Serializable
data class DetectorStatsDto(
    /** In milliseconds. */
    @SerialName("inference_speed") val inferenceSpeed: Double = 0.0,
)

/** Percentages, which Frigate sends as text. */
@Serializable
data class ProcessUsageDto(
    val cpu: String? = null,
    val mem: String? = null,
)

/** The space a camera's recordings take, in mebibytes. */
@Serializable
data class CameraStorageDto(
    val usage: Double = 0.0,
    @SerialName("usage_percent") val usagePercent: Double? = null,
)

/** Frigate's configuration profiles (e.g. "away"). */
@Serializable
data class FrigateProfilesDto(
    @SerialName("active_profile") val activeProfile: String? = null,
)
