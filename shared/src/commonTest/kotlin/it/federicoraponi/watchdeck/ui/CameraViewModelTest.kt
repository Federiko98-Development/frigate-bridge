package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.server.Fixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakePeer : LivePeer {
    val answer = CompletableDeferred<String>()

    override suspend fun createOffer() = "offer"

    override suspend fun acceptAnswer(sdp: String) {
        answer.complete(sdp)
    }
}

class CameraViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    private suspend fun frontDoor(): CameraViewModel {
        fixture.saveBoth()
        val viewModel =
            CameraViewModel(
                CameraArgs(CameraRef("home", "front_door")),
                fixture.registry,
                fixture.monitor,
                fixture.settings,
            )
        viewModel.state.first { it.streams.isNotEmpty() }
        return viewModel
    }

    @Test
    fun leavesTheLiveViewAloneSwitchingDetectionBeforeMotion() =
        runTest {
            fixture.home.config =
                """{"cameras": {"front_door": {"record": {"enabled": true}, "detect": {"enabled": true},""" +
                """"motion": {"enabled": true}}}}"""
            val viewModel = frontDoor()
            viewModel.state.first { it.controls.enabled.isNotEmpty() }

            viewModel.setLiveOnly(true)
            viewModel.state.first { !it.controls.isApplying }

            assertEquals(
                listOf("recordings", "detect", "motion").map { """$it={"value":"OFF"}""" },
                fixture.home.cameraChanges,
            )
            assertEquals(
                setOf("recordings", "detect", "motion"),
                fixture.settings.settings
                    .first()
                    .liveOnlyRestore["home/front_door"],
            )
        }

    @Test
    fun bringsBackWhatWasOnBeforeTheLiveViewAlone() =
        runTest {
            fixture.home.config = """{"cameras": {"front_door": {"record": {"enabled_in_config": true}}}}"""
            fixture.settings.update {
                it.copy(liveOnlyRestore = mapOf("home/front_door" to setOf("detect", "motion", "audio")))
            }
            val viewModel = frontDoor()
            viewModel.state.first { it.controls.isLiveOnly }

            viewModel.setLiveOnly(false)
            viewModel.state.first { !it.controls.isApplying }

            assertEquals(
                listOf("motion", "detect", "audio").map { """$it={"value":"ON"}""" },
                fixture.home.cameraChanges,
            )
            assertEquals(
                emptyMap(),
                fixture.settings.settings
                    .first()
                    .liveOnlyRestore,
            )
        }

    @Test
    fun recordsAClipAndPreparesItsFile() =
        runTest {
            fixture.home.config = """{"cameras": {"front_door": {"record": {"enabled": true}}}}"""
            val viewModel = frontDoor()
            viewModel.state.first { it.canRecordClip }

            viewModel.startClip()
            viewModel.state.first { it.clip is ClipState.Recording }
            assertEquals(
                setOf("home/front_door/manual"),
                fixture.settings.settings
                    .first()
                    .openRecordings,
            )

            viewModel.stopClip()

            val ready = viewModel.state.first { it.clip is ClipState.Ready }.clip as ClipState.Ready
            assertEquals("clip.mp4", ready.file.fileName)
            assertEquals(ready, viewModel.takeClip())
            assertEquals(null, viewModel.takeClip())
            assertEquals(
                emptySet(),
                fixture.settings.settings
                    .first()
                    .openRecordings,
            )
        }

    @Test
    fun forgetsALeftOverRecordingThatHasAlreadyEndedWithoutEndingItAgain() =
        runTest {
            fixture.settings.update { it.copy(openRecordings = setOf("home/front_door/manual")) }

            frontDoor()

            fixture.settings.settings.first { it.openRecordings.isEmpty() }
            assertTrue(fixture.home.requests.none { it.url.encodedPath.endsWith("/events/manual/end") })
        }

    @Test
    fun negotiatesTheSelectedStreamThroughThePeer() =
        runTest {
            val viewModel = frontDoor()
            val peer = FakePeer()

            viewModel.connectLive(peer)

            assertEquals("answer-for-front_door", peer.answer.await())
        }
}
