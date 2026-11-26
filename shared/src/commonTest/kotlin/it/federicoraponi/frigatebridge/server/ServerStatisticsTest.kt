package it.federicoraponi.frigatebridge.server

import it.federicoraponi.frigatebridge.frigate.StatsDto
import it.federicoraponi.frigatebridge.frigate.frigateJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerStatisticsTest {
    @Test
    fun readsTheFiguresTheServerSends() {
        val stats =
            frigateJson.decodeFromString<StatsDto>(
                """
                {
                  "service": {"uptime": 60, "version": "0.18.3-abc", "latest_version": "0.19.0",
                    "storage": {"/media/frigate/recordings": {"total": 100.0, "used": 40.0, "free": 60.0}}},
                  "cameras": {"front_door": {"camera_fps": 5.1, "skipped_fps": 0.0, "pid": 12}},
                  "detectors": {"ov": {"inference_speed": 8.5}},
                  "cpu_usages": {"frigate.full_system": {"cpu": "12.5", "mem": "40.0"}}
                }
                """.trimIndent(),
            )

        val read = serverStatistics(stats, emptyMap())

        assertEquals("0.19.0", read.newerVersion)
        assertEquals(DiskUse(100.0, 40.0, 60.0), read.disk)
        assertEquals(12.5, read.processorPercent)
        assertEquals(5.1, read.cameras.single().framesPerSecond)
    }

    @Test
    fun comparesVersions() {
        assertTrue(isNewerVersion("0.18.4", "0.18.3-abc"))
        assertFalse(isNewerVersion("0.18.3", "0.18.3-abc"))
        assertFalse(isNewerVersion("unknown", "0.18.3"))
    }
}
