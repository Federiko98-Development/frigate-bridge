package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val viewModel = CameraViewModel(CameraArgs(CameraRef("home", "front_door")), fixture.registry, fixture.monitor)
        viewModel.state.first { it.streams.isNotEmpty() }
        return viewModel
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
