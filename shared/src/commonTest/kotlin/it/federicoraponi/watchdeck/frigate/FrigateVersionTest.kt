package it.federicoraponi.watchdeck.frigate

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrigateVersionTest {
    @Test
    fun acceptsTheMinimumAndNewerReleases() {
        assertTrue(isSupportedFrigateVersion("0.18.0"))
        assertTrue(isSupportedFrigateVersion("0.18.3-abc1234"))
        assertTrue(isSupportedFrigateVersion("0.20.1"))
        assertTrue(isSupportedFrigateVersion("1.0"))
    }

    @Test
    fun rejectsOlderReleases() {
        assertFalse(isSupportedFrigateVersion("0.17.1"))
        assertFalse(isSupportedFrigateVersion("0.9.0"))
    }

    @Test
    fun doesNotWarnAboutVersionsItCannotRead() {
        assertTrue(isSupportedFrigateVersion("dev"))
    }
}
