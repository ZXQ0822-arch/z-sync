package app.zsync.gallery

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraFileTest {
    private val files = listOf(
        CameraFile(1, "DSC_0101.JPG", 4_800_000, PhotoFormat.JPEG, "DSC_0101"),
        CameraFile(2, "DSC_0101.NEF", 22_000_000, PhotoFormat.NEF, "DSC_0101"),
        CameraFile(3, "DSC_0102.JPG", 5_100_000, PhotoFormat.JPEG, "DSC_0102", locked = true),
        CameraFile(4, "DSC_0102.NEF", 21_000_000, PhotoFormat.NEF, "DSC_0102", locked = true),
        CameraFile(5, "DSC_0103.JPG", 3_200_000, PhotoFormat.JPEG, "DSC_0103"),
    )

    @Test
    fun deletingAJpegAlsoRemovesItsRaw() {
        val plan = planDelete(files, setOf(1))
        assertEquals(setOf(1L, 2L), plan.remove)
        assertTrue(plan.keptLocked.isEmpty())
    }

    @Test
    fun aProtectedPairStays() {
        val plan = planDelete(files, setOf(3))
        assertTrue(plan.remove.isEmpty())
        assertEquals(listOf("DSC_0102.JPG", "DSC_0102.NEF"), plan.keptLocked)
    }

    @Test
    fun aMixedSelectionKeepsOnlyTheProtectedBurst() {
        val plan = planDelete(files, setOf(1, 4))
        assertEquals(setOf(1L, 2L), plan.remove)
        assertEquals(listOf("DSC_0102.JPG", "DSC_0102.NEF"), plan.keptLocked)
    }

    @Test
    fun savingLeavesSelectionAndKeepsTheChosenPhotos() {
        val batch = saveBatch(files, setOf(1, 5))
        assertEquals(listOf(files[0], files[4]), batch?.picks)
        assertEquals(false, batch?.selecting)
    }

    @Test
    fun savingDoesNotStartWithoutAChosenPhoto() {
        assertNull(saveBatch(files, emptySet()))
        assertNull(saveBatch(files, setOf(99)))
    }

    @Test
    fun syncRequestsUseTheFileNameSizeCaptureTimeAndHandle() {
        val timed = files[0].copy(
            capturedLocalAt = LocalDateTime.of(2026, 10, 21, 21, 59),
            handle = 42,
        )
        val request = timed.syncTransfer()
        assertEquals(TransferKind.Sync, request.kind)
        assertEquals(42L, request.handle)
        assertEquals(TransferFormat.JPEG, request.format)
        assertEquals("DSC_0101.JPG", request.identity.name)
        assertEquals(4_800_000L, request.identity.bytes)
        assertEquals("2026-10-21T21:59:00", request.identity.capturedAt)

        val noDate = files[4]
        val unknown = noDate.syncTransfer()
        assertEquals(TransferFormat.JPEG, unknown.format)
        assertEquals(null, unknown.identity.capturedAt)
        assertEquals(
            listOf(timed.syncTransfer(), noDate.syncTransfer()),
            syncTransfers(listOf(timed, noDate)),
        )
        val queued = beginSync(listOf(timed)).queue
        assertEquals(42L, handleFor(queued, timed.syncTransfer().identity))
        assertTrue(timed.syncTransfer().identity.sameFileAs(timed))
    }

    @Test
    fun anEmptyCameraStaysDescribedAsNoPhotos() {
        assertEquals("相机里没有照片", statusAfterDelete(remaining = 0, current = "已显示 2 / 2 张"))
        assertEquals("已显示 1 / 2 张", statusAfterDelete(remaining = 1, current = "已显示 1 / 2 张"))
    }

    @Test
    fun stillsDownloadTheirOriginalAndVideoDoesNot() {
        assertTrue(downloadsOriginal(PhotoFormat.JPEG))
        assertTrue(downloadsOriginal(PhotoFormat.NEF))
        assertFalse(downloadsOriginal(PhotoFormat.MOV))
        assertFalse(downloadsOriginal(PhotoFormat.MP4))
    }

    @Test
    fun gridCountsMatchTheScreenLayout() {
        assertEquals(2, GridCount.FOUR.columns)
        assertEquals(2, GridCount.FOUR.rows)
        assertEquals(3, GridCount.SIX.rows)
        assertEquals(3, GridCount.NINE.columns)
    }

    @Test
    fun cameraDateDoesNotChangeAcrossPhoneTimezones() {
        val local = LocalDateTime.of(2026, 10, 21, 0, 30)
        val file = files[0].copy(capturedLocalAt = local)

        assertEquals(LocalDate.of(2026, 10, 21), file.captureDate)
        assertNotEquals(
            file.capturedAtMillis(ZoneId.of("Pacific/Kiritimati")),
            file.capturedAtMillis(ZoneId.of("America/Los_Angeles")),
        )
        assertEquals(LocalDate.of(2026, 10, 21), file.captureDate)
        assertEquals(file.captureDate, file.cameraDateKey())
        assertEquals("10月21日", dateHeading(file.cameraDateKey()))
        val shifted = java.time.Instant.ofEpochMilli(file.capturedAtMillis(ZoneId.of("Pacific/Kiritimati"))!!)
            .atZone(ZoneId.of("America/Los_Angeles"))
            .toLocalDate()
        assertNotEquals(shifted, file.cameraDateKey())
        assertEquals(UNRECORDED_DATE, dateHeading(null))
    }

    @Test
    fun oldestFirstUsesCameraLocalTimeThenSequence() {
        val early = files[0].copy(capturedLocalAt = LocalDateTime.of(2026, 10, 21, 8, 0), sequence = 9)
        val late = files[4].copy(capturedLocalAt = LocalDateTime.of(2026, 10, 21, 9, 0), sequence = 1)
        val undated = files[2].copy(capturedLocalAt = null, sequence = 3)
        val ordered = sortedPhotos(listOf(late, undated, early), PhotoOrder.OLDEST)
        assertEquals(listOf(early.handle, late.handle, undated.handle), ordered.map { it.handle })
        assertEquals(listOf(undated.handle, late.handle, early.handle), sortedPhotos(ordered, PhotoOrder.NEWEST).map { it.handle })
    }

    @Test
    fun fileSizeUsesMegabytesForOriginals() {
        assertEquals("4.6 MB", formatBytes(4_800_000))
        assertEquals("21.0 MB", formatBytes(22_020_096))
    }
}
