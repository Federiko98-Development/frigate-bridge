package it.federicoraponi.watchdeck.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/** How the cameras of a server are laid out. */
@Serializable
enum class HomeLayout {
    Grid,

    /** One camera below the other, as wide as the screen. */
    List,
}

/** What the buttons of the camera groups show. */
@Serializable
enum class GroupButtons {
    Names,

    /** The group's icon, where the app knows it; otherwise its name. */
    Icons,
}

/** How often the pictures of the cameras are loaded again. */
@Serializable
enum class PreviewRefresh(
    /** `null`: only when the cameras are opened. */
    val intervalMillis: Long?,
) {
    Fast(1_500),
    Slow(5_000),
    OnOpen(null),
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    /** Colours taken from the phone's wallpaper, where the system offers them, instead of the app's blue. */
    val dynamicColor: Boolean = false,
    val homeLayout: HomeLayout = HomeLayout.Grid,
    val groupButtons: GroupButtons = GroupButtons.Names,
    val previewRefresh: PreviewRefresh = PreviewRefresh.Fast,
    /** On a metered connection, load pictures less often. */
    val savePreviewData: Boolean = true,
    /** The app asks who is using it, with a fingerprint, the face or the phone's own code. */
    val appLock: Boolean = false,
    /**
     * Recordings started on demand and not yet ended, as `serverId/camera/eventId`: ended later if the app was
     * closed meanwhile, as the server would otherwise go on with them.
     */
    val openRecordings: Set<String> = emptySet(),
    /**
     * For each camera put in live view only, as `serverId/camera`: Frigate's names of the functions that were on, to
     * switch them back on.
     */
    val liveOnlyRestore: Map<String, Set<String>> = emptyMap(),
    /** Names of the cameras switched off in each group, kept by `camera/CameraSelection.kt`. */
    val hiddenCameras: Map<String, Set<String>> = emptyMap(),
) {
    /** The pause between two pictures of a camera, or `null` to load them only once. */
    fun previewIntervalMillis(onMeteredNetwork: Boolean): Long? {
        val chosen = previewRefresh.intervalMillis ?: return null
        return if (onMeteredNetwork && savePreviewData) maxOf(chosen, METERED_PREVIEW_INTERVAL_MILLIS) else chosen
    }
}

private const val METERED_PREVIEW_INTERVAL_MILLIS = 10_000L
