package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.recordings.momentOfDay
import it.federicoraponi.frigatebridge.recordings.recordingDay
import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
