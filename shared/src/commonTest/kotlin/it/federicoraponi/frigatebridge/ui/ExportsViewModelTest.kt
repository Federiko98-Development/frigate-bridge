package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.server.Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportsViewModelTest : ViewModelTest() {
    private val fixture = Fixture()

    @Test
    fun listsEveryExportOrOnlyTheKeptMoments() =
        runTest {
            fixture.saveBoth()
            fixture.registry.activate("home")
            fixture.home.exports =
                """
                [
                  {"id": "a", "camera": "front_door", "name": "Person #saved-12.5-abc", "date": 20},
                  {"id": "b", "camera": "front_door", "name": "front_door clip", "date": 10}
                ]
                """.trimIndent()

            val all = ExportsViewModel(ExportsArgs(savedOnly = false), fixture.registry)
            all.load()
            assertEquals(
                listOf("a", "b"),
                all.state
                    .first { !it.isLoading }
                    .items
                    .map { it.id },
            )

            val saved = ExportsViewModel(ExportsArgs(savedOnly = true), fixture.registry)
            saved.load()
            val item =
                saved.state
                    .first { !it.isLoading }
                    .items
                    .single()
            assertEquals("Person", item.title)
            assertEquals("12.5-abc", item.savedReviewId)
        }
}
