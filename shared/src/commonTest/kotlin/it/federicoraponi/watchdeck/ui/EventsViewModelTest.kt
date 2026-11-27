package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.frigate.FakeFrigate
import it.federicoraponi.watchdeck.recordings.momentOfDay
import it.federicoraponi.watchdeck.recordings.recordingDay
import it.federicoraponi.watchdeck.server.Fixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class EventsViewModelTest : ViewModelTest() {
    private val fixture = Fixture()
    private val zoneId = TimeZone.currentSystemDefault().id

    private suspend fun events(): EventsViewModel {
        fixture.saveBoth()
        fixture.registry.activate("home")
        val viewModel = EventsViewModel(fixture.registry, fixture.settings, fixture.monitor)
        viewModel.state.first { !it.isLoading }
        return viewModel
    }

    private fun lastQuery(name: String) =
        fixture.home.requests
            .last { it.url.encodedPath.endsWith("/api/review") }
            .url.parameters[name]

    @Test
    fun deletesTheChosenMoments() =
        runTest {
            fixture.home.reviews = "[${FakeFrigate.MOMENT}]"
            val viewModel = events()
            val moment =
                viewModel.state.value.items
                    .single()

            viewModel.toggleSelection(moment)
            assertEquals(
                listOf(moment),
                viewModel.state.value.deletable,
            )
            viewModel.deleteSelected()

            val state = viewModel.state.first { !it.isDeleting && it.items.isEmpty() }
            assertEquals(emptySet(), state.selected)
            assertEquals(listOf("""{"ids":["moment"]}"""), fixture.home.deletedReviews)
        }

    @Test
    fun letsOnlyAdministratorsDelete() =
        runTest {
            fixture.home.reviews = "[${FakeFrigate.MOMENT}]"
            fixture.home.users[fixture.home.username] = "viewer"
            val viewModel = events()

            viewModel.selectAll()
            viewModel.deleteSelected()

            assertEquals(emptyList(), fixture.home.deletedReviews)
            assertEquals(
                1,
                viewModel.state.value.items.size,
            )
        }

    @Test
    fun removesTheFilesCutForSharing() =
        runTest {
            fixture.home.reviews = "[${FakeFrigate.MOMENT}]"
            val viewModel = events()

            viewModel.selectAll()
            viewModel.shareSelected()
            val ready = viewModel.state.first { it.share is SelectionShare.Ready }.share as SelectionShare.Ready
            assertEquals(listOf("clip.mp4"), ready.files.map { it.fileName })
            assertEquals(emptyList(), fixture.home.deletedExports)

            viewModel.endSharing(shared = true)

            assertEquals(
                emptySet(),
                viewModel.state.value.selected,
            )
            // The removal runs in the background, on the fake server's own thread.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { while (fixture.home.deletedExports.isEmpty()) delay(10) }
            }
            assertEquals(listOf("""{"ids":["clip"]}"""), fixture.home.deletedExports)
        }

    @Test
    fun asksForTheChosenDayFromTheChosenTime() =
        runTest {
            val viewModel = events()
            val today = recordingDay(viewModel.state.value.today, zoneId)!!
            assertEquals(today.start.toString(), lastQuery("after"))
            assertEquals(today.end.toString(), lastQuery("before"))

            viewModel.setMoment("2020-03-10", fromMinute = 14 * 60 + 30)
            viewModel.state.first { !it.isLoading && it.date == "2020-03-10" }

            assertEquals(momentOfDay("2020-03-10", 870, zoneId).toString(), lastQuery("after"))
            assertEquals(recordingDay("2020-03-10", zoneId)!!.end.toString(), lastQuery("before"))
        }

    @Test
    fun asksOnlyForTheCamerasSwitchedOn() =
        runTest {
            val viewModel = events()
            assertEquals(null, lastQuery("cameras"))

            viewModel.showCameras(null, setOf("garden"))
            viewModel.state.first { !it.isLoading && it.choices.first().isPartial }

            assertEquals("garden", lastQuery("cameras"))
        }
}
