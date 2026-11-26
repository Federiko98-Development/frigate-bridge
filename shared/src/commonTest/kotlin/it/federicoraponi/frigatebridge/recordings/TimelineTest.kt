package it.federicoraponi.frigatebridge.recordings

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineTest {
    // Two files in a row, a pause of ten minutes, then a third one.
    private val segments =
        listOf(
            RecordingSegment(100.0, 110.0),
            RecordingSegment(110.5, 120.5),
            RecordingSegment(720.0, 730.0),
        )

    @Test
    fun joinsConsecutiveFilesIntoRanges() {
        assertEquals(listOf(TimeRange(100, 120), TimeRange(720, 730)), recordedRanges(segments))
    }

    @Test
    fun skipsThePausesWhenTranslatingATimeIntoAPosition() {
        val window = PlaybackWindow(0, 3600, segments)

        assertEquals(5_000, window.positionMillisAt(105))
        assertEquals(20_000, window.positionMillisAt(400))
        assertEquals(25_000, window.positionMillisAt(725))
    }

    @Test
    fun translatesAPositionBackIntoTheTimeOfDay() {
        val window = PlaybackWindow(0, 3600, segments)

        assertEquals(105, window.timeAt(5_000))
        assertEquals(725, window.timeAt(25_000))
    }

    @Test
    fun cutsTheFilesAtTheEdgesOfTheWindow() {
        val window = PlaybackWindow(105, 725, segments)

        assertEquals(0, window.positionMillisAt(105))
        assertEquals(720, window.timeAt(15_000))
    }
}
