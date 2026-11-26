package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    @Test
    fun stopsWhereNothingWasRecordedAndJumpsOverThePause() =
        runTest {
            fixture.saveBoth()
            fixture.home.recordings =
                """[{"start_time": 1000, "end_time": 1200}, {"start_time": 2000, "end_time": 2100}]"""
            val args = TimelineArgs(CameraRef("home", "front_door"), zoneId = "UTC")
            val viewModel = TimelineViewModel(args, fixture.registry, fixture.monitor)
            viewModel.state.first { it.playback is PlaybackState.Ready }

            viewModel.seekTo(1500)

            val gap = viewModel.state.first { it.playback is PlaybackState.Gap }
            assertEquals(1500, gap.time)
            assertEquals(PlaybackState.Gap(hasEarlier = true, hasLater = true), gap.playback)

            viewModel.skip(-10)

            assertEquals(1190, viewModel.state.first { it.playback is PlaybackState.Ready }.time)
        }
}
