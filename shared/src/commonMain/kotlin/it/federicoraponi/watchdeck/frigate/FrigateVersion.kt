package it.federicoraponi.watchdeck.frigate

/** The oldest Frigate release the app supports. */
const val MINIMUM_FRIGATE_VERSION = "0.18"

/**
 * Whether [version] (as returned by `/api/version`, e.g. `0.18.3-abc1234`) is at least [MINIMUM_FRIGATE_VERSION].
 * A version that cannot be parsed is treated as supported, so development builds do not trigger a warning.
 */
fun isSupportedFrigateVersion(version: String): Boolean {
    val (major, minor) = parseMajorMinor(version) ?: return true
    val (minimumMajor, minimumMinor) = checkNotNull(parseMajorMinor(MINIMUM_FRIGATE_VERSION))
    return major > minimumMajor || (major == minimumMajor && minor >= minimumMinor)
}

private fun parseMajorMinor(version: String): Pair<Int, Int>? {
    val match = Regex("""^\s*v?(\d+)\.(\d+)""").find(version) ?: return null
    val (major, minor) = match.destructured
    return major.toInt() to minor.toInt()
}
