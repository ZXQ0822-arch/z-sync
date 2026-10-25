package app.zsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MediaBrowseTest {
    @Test
    fun pagingUsesTheVisibleMediaPositionRatherThanTheLazyRow() {
        val rows = listOf(
            CatalogRow.Heading(null, "10月21日"),
            CatalogRow.Media(shot(1, PhotoFormat.JPEG)),
            CatalogRow.Heading(null, "10月22日"),
            CatalogRow.Media(shot(2, PhotoFormat.NEF)),
            CatalogRow.Heading(null, "10月23日"),
            CatalogRow.Media(shot(3, PhotoFormat.MOV)),
            CatalogRow.Heading(null, "日期未记录"),
            CatalogRow.Media(shot(4, PhotoFormat.MP4)),
        )
        assertEquals(0, mediaPosition(rows, 0))
        assertEquals(0, mediaPosition(rows, 1))
        assertEquals(3, mediaPosition(rows, 7))
        assertFalse(shouldLoadMore(mediaPosition(rows, 7), loaded = 10, total = 40))
        assertEquals(true, shouldLoadMore(7, loaded = 10, total = 40))
    }

    @Test
    fun aHiddenHandleStaysSelectedAndPreviewOnlyWalksTheVisibleFiles() {
        val files = listOf(
            shot(1, PhotoFormat.JPEG),
            shot(2, PhotoFormat.NEF),
            shot(3, PhotoFormat.MOV),
        )
        val visible = visibleMedia(files, MediaFilter.Jpeg)
        assertEquals(setOf(2L, 3L), selectionAfterFilter(setOf(2L, 3L), visible))
        assertEquals(listOf(1L), visible.map { it.handle })
    }

    @Test
    fun videoPlaysInTheAppAfterItIsOnThePhoneAndFallsBackWhenThatFails() {
        assertEquals(PlaybackKind.Photo, playbackKind(PhotoFormat.JPEG, savedOnPhone = false, inAppFailed = false, systemFailed = false))
        assertEquals(PlaybackKind.NeedsSync, playbackKind(PhotoFormat.MOV, savedOnPhone = false, inAppFailed = false, systemFailed = false))
        assertEquals(PlaybackKind.InApp, playbackKind(PhotoFormat.MP4, savedOnPhone = true, inAppFailed = false, systemFailed = false))
        assertEquals(PlaybackKind.System, playbackKind(PhotoFormat.MOV, savedOnPhone = true, inAppFailed = true, systemFailed = false))
        assertEquals(PlaybackKind.Failed, playbackKind(PhotoFormat.MP4, savedOnPhone = true, inAppFailed = true, systemFailed = true))
        assertEquals("video/quicktime", videoMime(PhotoFormat.MOV))
        assertEquals("video/mp4", videoMime(PhotoFormat.MP4))
        assertEquals(VIDEO_NEEDS_SYNC, "同步到相册后可以播放")
        assertEquals(VIDEO_OPEN_FAILED, "这部视频打不开")
    }
}

private fun shot(handle: Long, format: PhotoFormat) = CameraFile(
    handle = handle,
    name = "DSC_$handle",
    bytes = 10,
    format = format,
    burstKey = "DSC_$handle",
)
