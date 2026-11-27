package it.federicoraponi.watchdeck.network

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionsTest {
    private val offline = NetworkStatus(isOnline = false)
    private val wifi = NetworkStatus(isOnline = true, id = 1)
    private val mobile = NetworkStatus(isOnline = true, id = 2)

    private suspend fun count(vararg status: NetworkStatus) = flowOf(*status).connections().toList().size

    @Test
    fun countsTheStartEveryReturnAndEveryChangeOfNetwork() =
        runTest {
            assertEquals(1, count(wifi, wifi))
            assertEquals(1, count(wifi, offline))
            assertEquals(2, count(wifi, offline, wifi))
            assertEquals(2, count(wifi, mobile))
            assertEquals(2, count(offline, wifi))
        }
}
