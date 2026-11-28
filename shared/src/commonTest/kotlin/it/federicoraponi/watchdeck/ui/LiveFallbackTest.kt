package it.federicoraponi.watchdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveFallbackTest {
    private val streams = listOf("main", "sub")

    /** Fails every way offered, each time for [reason], and lists the ways in the order they were tried. */
    private fun waysTried(
        streams: List<String>,
        reason: VideoProblem,
    ): List<LiveAttempt> {
        val setbacks = mutableListOf<LiveSetback>()
        while (true) {
            val next = nextLiveAttempt(streams, "main", setbacks) ?: return setbacks.map { it.attempt }
            setbacks += LiveSetback(next, VideoFailure(reason = reason))
        }
    }

    @Test
    fun triesAnotherStreamWhenTheTroubleMayBeTheStream() {
        assertEquals(
            listOf(
                LiveAttempt(LiveTransport.Https, "main"),
                LiveAttempt(LiveTransport.WebRtc, "main"),
                LiveAttempt(LiveTransport.Https, "sub"),
                LiveAttempt(LiveTransport.Snapshots, "main"),
            ),
            waysTried(streams, VideoProblem.UnsupportedFormat),
        )
    }

    @Test
    fun goesStraightToPicturesWhenTheConnectionIsTheTrouble() {
        assertEquals(
            listOf(LiveTransport.Https, LiveTransport.WebRtc, LiveTransport.Snapshots),
            waysTried(streams, VideoProblem.Connection).map { it.transport },
        )
    }

    @Test
    fun endsAfterThePictures() {
        val all = waysTried(listOf("main"), VideoProblem.NoPicture).map { LiveSetback(it, VideoFailure()) }

        assertEquals(3, all.size)
        assertNull(nextLiveAttempt(listOf("main"), "main", all))
    }

    @Test
    fun triesNothingElseWhenEveryWayWouldFailAlike() {
        assertTrue(VideoFailure(problem = ConnectionProblem.SignInRequired).stopsEveryWay)
        assertTrue(VideoFailure(problem = ConnectionProblem.Unreachable).stopsEveryWay)
        assertFalse(VideoFailure(reason = VideoProblem.Refused).stopsEveryWay)
        assertFalse(VideoFailure(problem = ConnectionProblem.UnexpectedResponse).stopsEveryWay)
    }
}
