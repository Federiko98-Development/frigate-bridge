package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    @Test
    fun playsTheMomentWithALittleBeforeAndAfterIt() =
        runTest {
            fixture.saveBoth()
            val viewModel = EventViewModel(EventArgs("home", "moment"), fixture.registry, fixture.monitor)

            val state = viewModel.state.first { it.playback is PlaybackState.Ready }

            assertEquals("Front door", state.cameraName)
            assertEquals(listOf("person"), state.item?.labels)
            val clip = assertIs<PlaybackState.Ready>(state.playback).clip
            assertEquals(1996L, clip.startTime)
            assertEquals(2029L, clip.endTime)
        }

    @Test
    fun reportsAMomentTheServerNoLongerHas() =
        runTest {
            fixture.saveBoth()
            val viewModel = EventViewModel(EventArgs("home", "gone"), fixture.registry, fixture.monitor)

            val state = viewModel.state.first { it.playback is PlaybackState.Failed }

            val failure = assertIs<PlaybackState.Failed>(state.playback).failure
            assertTrue(failure.detail.orEmpty().contains("404"))
        }
}
