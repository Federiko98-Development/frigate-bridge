package it.federicoraponi.frigatebridge.recordings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingDayTest {
    @Test
    fun startsAndEndsAtLocalMidnight() {
        assertEquals(RecordingDay("1970-01-02", 82_800, 169_200), recordingDay("1970-01-02", "Europe/Rome"))
    }

    @Test
    fun ignoresADateItCannotRead() {
        assertNull(recordingDay("yesterday", "UTC"))
    }
}
