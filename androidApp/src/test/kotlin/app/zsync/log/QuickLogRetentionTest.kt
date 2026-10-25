package app.zsync.log

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class QuickLogRetentionTest {
    private val hour = 60L * 60 * 1000

    private fun log(name: String, ageMs: Long, now: Long): File {
        val dir = Files.createTempDirectory("quicklog").toFile()
        return File(dir, name).apply {
            writeText("x")
            setLastModified(now - ageMs)
        }
    }

    @Test
    fun anythingOlderThanSixHoursGoes() {
        val now = 1_700_000_000_000L
        val fresh = log("z-sync-fresh.txt", ageMs = 1 * hour, now = now)
        val stale = log("z-sync-stale.txt", ageMs = 7 * hour, now = now)

        val doomed = QuickLog.expiringLogs(listOf(fresh, stale), now)

        assertEquals(listOf(stale), doomed)
    }

    @Test
    fun aFileExactlyAtTheEdgeIsKept() {
        val now = 1_700_000_000_000L
        val edge = log("z-sync-edge.txt", ageMs = 6 * hour, now = now)

        assertTrue(QuickLog.expiringLogs(listOf(edge), now).isEmpty())
    }

    @Test
    fun theNewestFiveSurviveEvenWhenAllAreRecent() {
        val now = 1_700_000_000_000L
        val logs = (1..8).map { n -> log("z-sync-$n.txt", ageMs = n * 60_000L, now = now) }

        val doomed = QuickLog.expiringLogs(logs, now)

        assertEquals(3, doomed.size)
        // The three oldest minutes go; the newest five stay.
        assertEquals(
            setOf("z-sync-8.txt", "z-sync-7.txt", "z-sync-6.txt"),
            doomed.map { it.name }.toSet(),
        )
    }

    @Test
    fun nothingToDoOnAnEmptyFolder() {
        assertTrue(QuickLog.expiringLogs(emptyList(), 1_700_000_000_000L).isEmpty())
    }
}
