package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.frigate.CameraStorageDto
import it.federicoraponi.watchdeck.frigate.StatsDto

private const val RECORDINGS_PATH = "/media/frigate/recordings"
private const val WHOLE_SYSTEM = "frigate.full_system"

/** Space on a disk, in mebibytes. */
data class DiskUse(
    val total: Double,
    val used: Double,
    val free: Double,
)

data class CameraStatistics(
    val name: String,
    /** Frames received each second; zero means that nothing arrives from the camera. */
    val framesPerSecond: Double,
    val skippedPerSecond: Double,
    /** Space taken by its recordings, in mebibytes, if the server told. */
    val storage: Double?,
    /** That space as a part of the whole disk, from 0 to 100. */
    val storagePercent: Double?,
)

data class DetectorStatistics(
    val name: String,
    val inferenceMillis: Double,
)

/** How a server is doing. */
data class ServerStatistics(
    val version: String,
    /** A newer release the server knows of, or `null`. */
    val newerVersion: String?,
    val uptimeSeconds: Long,
    /** The disk holding the recordings. */
    val disk: DiskUse?,
    val cameras: List<CameraStatistics>,
    val detectors: List<DetectorStatistics>,
    val processorPercent: Double?,
    val memoryPercent: Double?,
)

internal fun serverStatistics(
    stats: StatsDto,
    storage: Map<String, CameraStorageDto>,
): ServerStatistics {
    val system = stats.cpuUsages[WHOLE_SYSTEM]
    val latest = stats.service.latestVersion
    return ServerStatistics(
        version = stats.service.version,
        newerVersion = latest.takeIf { isNewerVersion(it, stats.service.version) },
        uptimeSeconds = stats.service.uptime,
        disk = stats.service.storage[RECORDINGS_PATH]?.let { DiskUse(it.total, it.used, it.free) },
        cameras =
            stats.cameras.entries.sortedBy { it.key }.map { (name, camera) ->
                CameraStatistics(
                    name,
                    camera.cameraFps,
                    camera.skippedFps,
                    storage[name]?.usage,
                    storage[name]?.usagePercent,
                )
            },
        detectors = stats.detectors.entries.map { DetectorStatistics(it.key, it.value.inferenceSpeed) },
        processorPercent = system?.cpu?.toDoubleOrNull(),
        memoryPercent = system?.mem?.toDoubleOrNull(),
    )
}

/** Whether release [candidate] comes after [current], e.g. `0.19.0` after `0.18.3-abc`. Other text never does. */
internal fun isNewerVersion(
    candidate: String,
    current: String,
): Boolean {
    fun parts(version: String): List<Int>? =
        version
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: return null }

    val new = parts(candidate) ?: return false
    val old = parts(current) ?: return false
    for (index in 0 until maxOf(new.size, old.size)) {
        val difference = new.getOrElse(index) { 0 } - old.getOrElse(index) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}
