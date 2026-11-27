package it.federicoraponi.watchdeck.frigate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FrigateDownloadTest {
    private val server = FakeFrigate()

    @Test
    fun readsTheFileAndLogsInAgainWhenTheSessionHasExpired() =
        runTest {
            val api =
                FrigateApi(
                    server.client(),
                    "https://home.example.com",
                    { Credentials(server.username, server.password) },
                    InMemoryTokenStore(),
                )
            api.version()
            server.expireSession()
            val received = mutableListOf<Byte>()

            api.download("/media/frigate/exports/clip.mp4") { bytes, count -> received += bytes.take(count) }

            assertContentEquals(FakeFrigate.EXPORTED.encodeToByteArray(), received.toByteArray())
            assertEquals(2, server.loginCount)
        }
}
