package app.zsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MediaFilterTest {
    @Test
    fun theFiveFiltersMatchTheNamedFormatsAndKeepJpgWithJpeg() {
        assertEquals(
            listOf("全部", "JPEG", "RAW / NEF", "MOV", "MP4"),
            MediaFilter.entries.map { it.label },
        )
        val files = listOf(
            shot("A.JPG", PhotoFormat.JPEG, 1),
            shot("A.JPEG", PhotoFormat.JPEG, 2),
            shot("A.NEF", PhotoFormat.NEF, 3),
            shot("A.MOV", PhotoFormat.MOV, 4),
            shot("A.MP4", PhotoFormat.MP4, 5),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), visibleMedia(files, MediaFilter.All).map { it.handle })
        assertEquals(listOf(1L, 2L), visibleMedia(files, MediaFilter.Jpeg).map { it.handle })
        assertEquals(listOf(3L), visibleMedia(files, MediaFilter.Raw).map { it.handle })
        assertEquals(listOf(4L), visibleMedia(files, MediaFilter.Mov).map { it.handle })
        assertEquals(listOf(5L), visibleMedia(files, MediaFilter.Mp4).map { it.handle })
        assertEquals("没有这种格式", EMPTY_FILTER)
        assertEquals(emptyList(), visibleMedia(listOf(shot("A.MOV", PhotoFormat.MOV, 4)), MediaFilter.Jpeg))
    }

    @Test
    fun filteringReturnsAViewAndLeavesTheCatalogAlone() {
        val files = listOf(shot("A.JPG", PhotoFormat.JPEG, 1), shot("A.NEF", PhotoFormat.NEF, 2))
        val visible = visibleMedia(files, MediaFilter.Raw)
        assertEquals(listOf(2L), visible.map { it.handle })
        assertEquals(listOf(1L, 2L), files.map { it.handle })
        assertSame(files[1], visible.single())
    }

    @Test
    fun objectFormatWinsAndNdfOrUnknownNamesStayOut() {
        assertEquals(PhotoFormat.JPEG, userMediaFormat(0x3801, "DSC.NEF"))
        assertEquals(PhotoFormat.NEF, userMediaFormat(0x3000, "DSC.NEF"))
        assertEquals(PhotoFormat.JPEG, userMediaFormat(0x3000, "DSC.jpg"))
        assertEquals(PhotoFormat.MOV, userMediaFormat(0x300D, "CLIP.dat"))
        assertEquals(PhotoFormat.MP4, userMediaFormat(0xB982, "CLIP.bin"))
        assertEquals(PhotoFormat.MOV, userMediaFormat(0x3000, "CLIP.mov"))
        assertEquals(PhotoFormat.MP4, userMediaFormat(0x3000, "CLIP.MP4"))
        assertNull(userMediaFormat(0x3000, "DUST.NDF"))
        assertNull(userMediaFormat(0x3801, "DUST.NDF"))
        assertNull(userMediaFormat(0x3000, "NOTE.TXT"))
    }
}

private fun shot(name: String, format: PhotoFormat, handle: Long) = CameraFile(
    handle = handle,
    name = name,
    bytes = 10,
    format = format,
    burstKey = name.substringBeforeLast('.'),
)
