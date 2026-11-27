package it.federicoraponi.watchdeck.recordings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineTest {
    // Two files in a row, a pause of ten minutes, then a third one.
    private val segments =
        listOf(
            RecordingSegment(100.0, 110.0),
            RecordingSegment(110.5, 120.5),
            RecordingSegment(720.0, 730.0),
        )

    @Test
    fun keepsARecordedMomentAndNeverJumpsAgainstTheDirectionOfTravel() {
        val ranges = recordedRanges(segments)

        assertEquals(105, recordedMoment(ranges, 105, backwards = true))
        // In the pause: back to the end of the earlier recording, forward to the start of the later one.
        assertEquals(110, recordedMoment(ranges, 400, backwards = true))
        assertEquals(720, recordedMoment(ranges, 400, backwards = false))
        // Outside the day's recordings there is only one way to go.
        assertEquals(100, recordedMoment(ranges, 50, backwards = true))
        assertEquals(720, recordedMoment(ranges, 900, backwards = false))
        assertNull(recordedMoment(emptyList(), 400, backwards = true))
    }

    @Test
    fun holdsTheNearestPointOnlyWhenItIsClose() {
        val points = listOf(100L, 720L)

        assertEquals(100, snapPoint(points, 108.0, reach = 10.0))
        assertEquals(720, snapPoint(points, 712.5, reach = 10.0))
        assertNull(snapPoint(points, 400.0, reach = 10.0))
        assertNull(snapPoint(emptyList(), 400.0, reach = 10.0))
    }

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
