package app.zsync.gallery

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CameraDatesTest {
    @Test
    fun oneCameraDayKeepsEveryFormatTogether() {
        val morning = at(2026, 10, 21, 0, 5, "DSC_0001.JPG", PhotoFormat.JPEG, 1)
        val raw = at(2026, 10, 21, 0, 5, "DSC_0001.NEF", PhotoFormat.NEF, 2)
        val movie = at(2026, 10, 21, 18, 0, "DSC_0002.MOV", PhotoFormat.MOV, 3)
        val clip = at(2026, 10, 21, 18, 1, "DSC_0003.MP4", PhotoFormat.MP4, 4)
        val next = at(2026, 10, 22, 0, 1, "DSC_0004.JPG", PhotoFormat.JPEG, 5)
        val groups = groupByCameraDate(listOf(next, clip, movie, raw, morning), PhotoOrder.OLDEST)
        assertEquals(listOf(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22)), groups.map { it.key })
        assertEquals(listOf("10月21日", "10月22日"), groups.map { it.heading })
        assertEquals(listOf(1L, 2L, 3L, 4L), groups[0].items.map { it.handle })
    }

    @Test
    fun newestReversesTheDaysAndTheItemsInsideThem() {
        val early = at(2026, 10, 21, 8, 0, "A.JPG", PhotoFormat.JPEG, 1)
        val late = at(2026, 10, 22, 9, 0, "B.JPG", PhotoFormat.JPEG, 2)
        val groups = groupByCameraDate(listOf(early, late), PhotoOrder.NEWEST)
        assertEquals(listOf(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 21)), groups.map { it.key })
        assertEquals(listOf(2L), groups[0].items.map { it.handle })
    }

    @Test
    fun aMissingDateStaysUnknownInsteadOfToday() {
        val dated = at(2026, 10, 21, 12, 0, "A.JPG", PhotoFormat.JPEG, 1)
        val blank = dated.copy(handle = 9, name = "B.JPG", capturedLocalAt = null)
        val oldest = groupByCameraDate(listOf(blank, dated), PhotoOrder.OLDEST)
        assertEquals(UNRECORDED_DATE, oldest.last().heading)
        assertNull(oldest.last().key)
        assertEquals(listOf(9L), oldest.last().items.map { it.handle })
        val rows = catalogRows(oldest)
        assertEquals(2, rows.count { it is CatalogRow.Heading })
        assertEquals(2, mediaCount(rows))
        assertEquals(4, rows.size)
    }

    @Test
    fun twoYearsShowTheYearSoTheSameMonthDayStaysDistinct() {
        val first = at(2025, 10, 21, 1, 0, "A.JPG", PhotoFormat.JPEG, 1)
        val second = at(2026, 10, 21, 1, 0, "B.JPG", PhotoFormat.JPEG, 2)
        val groups = groupByCameraDate(listOf(first, second), PhotoOrder.OLDEST)
        assertEquals(listOf("2025年10月21日", "2026年10月21日"), groups.map { it.heading })
    }
}

private fun at(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    name: String,
    format: PhotoFormat,
    handle: Long,
) = CameraFile(
    handle = handle,
    name = name,
    bytes = 10,
    format = format,
    burstKey = name.substringBeforeLast('.'),
    capturedLocalAt = LocalDateTime.of(year, month, day, hour, minute),
    sequence = handle,
)
