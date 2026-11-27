package it.federicoraponi.watchdeck.camera

import it.federicoraponi.watchdeck.server.NameProblem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A camera group exactly as the configuration holds it. [cameras] may name cameras the user cannot see, or
 * [GroupRules.BIRDSEYE]. It is what an editor must start from: [CameraCatalog] leaves such members out.
 */
data class EditableGroup(
    val name: String,
    val cameras: List<String>,
    val icon: String,
    val order: Int,
)

/** A camera that can be put in a group or a role. */
data class CameraOption(
    val name: String,
    val displayName: String,
)

object GroupRules {
    const val MIN_LENGTH = 2

    /** The member of a group that stands for the view combining all cameras. */
    const val BIRDSEYE = "birdseye"

    /** Names of Lucide icons, which Frigate's web interface shows, offered by the icon picker. */
    val ICONS: List<String> =
        listOf(
            "LuHouse",
            "LuSofa",
            "LuArmchair",
            "LuBedDouble",
            "LuBedSingle",
            "LuUtensils",
            "LuCookingPot",
            "LuRefrigerator",
            "LuCoffee",
            "LuWine",
            "LuBath",
            "LuShowerHead",
            "LuToilet",
            "LuWashingMachine",
            "LuTv",
            "LuMonitor",
            "LuLightbulb",
            "LuLamp",
            "LuBookOpen",
            "LuDumbbell",
            "LuGamepad2",
            "LuCctv",
            "LuCamera",
            "LuDoorOpen",
            "LuDoorClosed",
            "LuFence",
            "LuShield",
            "LuLock",
            "LuKeyRound",
            "LuBell",
            "LuEye",
            "LuSiren",
            "LuMailbox",
            "LuPackage",
            "LuTrees",
            "LuTreePine",
            "LuTreeDeciduous",
            "LuFlower2",
            "LuSprout",
            "LuLeaf",
            "LuShovel",
            "LuWaves",
            "LuMountain",
            "LuTent",
            "LuUmbrella",
            "LuSun",
            "LuMoon",
            "LuCloudRain",
            "LuSnowflake",
            "LuFlame",
            "LuDroplets",
            "LuCar",
            "LuCircleParking",
            "LuTruck",
            "LuBus",
            "LuBike",
            "LuTractor",
            "LuSailboat",
            "LuPlane",
            "LuFuel",
            "LuPlugZap",
            "LuUser",
            "LuUsers",
            "LuBaby",
            "LuAccessibility",
            "LuPawPrint",
            "LuRabbit",
            "LuBird",
            "LuHeart",
            "LuBuilding2",
            "LuBuilding",
            "LuWarehouse",
            "LuFactory",
            "LuStore",
            "LuShoppingCart",
            "LuBriefcase",
            "LuSchool",
            "LuChurch",
            "LuHospital",
            "LuHotel",
            "LuWrench",
            "LuHammer",
            "LuServer",
            "LuCpu",
            "LuStar",
            "LuMapPin",
            "LuFlag",
            "LuBookmark",
            "LuLayoutGrid",
            "LuZap",
        )

    const val DEFAULT_ICON = "LuCctv"

    private val spaces = Regex("\\s+")

    /** Trims and turns every run of spaces into `_`, as the web interface does. */
    fun normalize(name: String): String = name.trim().replace(spaces, "_")

    /**
     * What is wrong with a [normalized] name. [original] is the group being edited, which may keep its own name,
     * even one these rules would not accept for a new group, as long as it has a letter. Only letters, digits,
     * `_` and `-` are accepted, so that the web interface can still edit the group.
     */
    fun nameProblem(
        normalized: String,
        existing: Collection<String>,
        original: String?,
    ): NameProblem? {
        // Frigate takes a name made of digits for a position in a list, and damages its configuration.
        val hasLetter = normalized.any { it.isLetter() }
        if (normalized == original) return if (hasLetter) null else NameProblem.NeedsLetter
        return when {
            normalized.isBlank() -> NameProblem.Missing
            normalized.length < MIN_LENGTH -> NameProblem.TooShort
            !normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> NameProblem.InvalidCharacters
            !hasLetter -> NameProblem.NeedsLetter
            normalized.equals("default", ignoreCase = true) -> NameProblem.Reserved
            normalized in existing -> NameProblem.Taken
            else -> null
        }
    }
}

// Changes of the configuration, sent as JSON: unlike a query string, it keeps names and types as they are.
// An empty text deletes a key, and Frigate applies the keys in order.

internal fun groupChange(
    originalName: String?,
    group: EditableGroup,
): JsonObject =
    buildJsonObject {
        putJsonObject("camera_groups") {
            // The old name goes first when a group is renamed.
            if (originalName != null && originalName != group.name) put(originalName, "")
            putJsonObject(group.name) {
                put("order", group.order)
                put("icon", group.icon)
                putJsonArray("cameras") { group.cameras.forEach { add(it) } }
            }
        }
    }

internal fun groupRemoval(name: String): JsonObject =
    buildJsonObject {
        putJsonObject("camera_groups") { put(name, "") }
    }

/** Writes the order alone: the other fields of each group stay as they are. */
internal fun groupOrderChange(names: List<String>): JsonObject =
    buildJsonObject {
        putJsonObject("camera_groups") {
            names.forEachIndexed { index, name -> putJsonObject(name) { put("order", index + 1) } }
        }
    }
