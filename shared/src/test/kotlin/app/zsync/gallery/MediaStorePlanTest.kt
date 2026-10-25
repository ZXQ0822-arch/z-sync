package app.zsync.gallery

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaStorePlanTest {

    @Test
    fun stillsGoToImagesAndVideoGoesToVideoWithItsOwnMime() {
        assertEquals(MediaCollection.Images, mediaCollection(PhotoFormat.JPEG))
        assertEquals(MediaCollection.Images, mediaCollection(PhotoFormat.NEF))
        assertEquals(MediaCollection.Video, mediaCollection(PhotoFormat.MOV))
        assertEquals(MediaCollection.Video, mediaCollection(PhotoFormat.MP4))
        assertEquals("image/jpeg", mediaMime(PhotoFormat.JPEG))
        assertEquals("image/x-nikon-nef", mediaMime(PhotoFormat.NEF))
        assertEquals("video/quicktime", mediaMime(PhotoFormat.MOV))
        assertEquals("video/mp4", mediaMime(PhotoFormat.MP4))
        assertEquals("Pictures/Z-Sync", mediaFolder(PhotoFormat.JPEG))
        assertEquals("Movies/Z-Sync", mediaFolder(PhotoFormat.MOV))
    }

    @Test
    fun aSavedCopyMatchesNameSizeFormatAndCollection() {
        val file = CameraFile(
            handle = 8,
            name = "DSC_0008.MOV",
            bytes = 40,
            format = PhotoFormat.MOV,
            burstKey = "DSC_0008",
            capturedLocalAt = LocalDateTime.of(2024, 10, 21, 23, 30),
        )
        val stored = StoredMedia(file.name, file.bytes, "video/quicktime", MediaCollection.Video)
        assertTrue(sameSavedFile(file, stored))
        assertFalse(sameSavedFile(file, stored.copy(bytes = 39)))
        assertFalse(sameSavedFile(file, stored.copy(name = "DSC_0009.MOV")))
        assertFalse(sameSavedFile(file, stored.copy(mime = "video/mp4")))
        assertFalse(sameSavedFile(file, stored.copy(collection = MediaCollection.Images)))
        val undated = file.copy(capturedLocalAt = null)
        assertTrue(sameSavedFile(undated, stored))
    }

    @Test
    fun aMediaStoreRenamedCopyStillMatches() {
        val file = CameraFile(
            handle = 8,
            name = "DSC_0008.JPG",
            bytes = 40,
            format = PhotoFormat.JPEG,
            burstKey = "DSC_0008",
            capturedLocalAt = LocalDateTime.of(2024, 10, 21, 23, 30),
        )
        val stored = StoredMedia("DSC_0008 (1).JPG", file.bytes, "image/jpeg", MediaCollection.Images)
        assertTrue(sameSavedFile(file, stored))
        assertTrue(sameSavedFile(file, stored.copy(name = "DSC_0008 (12).JPG")))
        assertFalse(sameSavedFile(file, stored.copy(name = "DSC_0009 (1).JPG")))
        assertFalse(sameSavedFile(file, stored.copy(name = "DSC_0008 (1).NEF")))
        assertFalse(sameSavedFile(file, stored.copy(name = "DSC_0008x.JPG")))
    }
}

class JpegSiblingNameTest {
    @Test
    fun aRawFindsTheJpegOfTheSameStem() {
        assertEquals("DSC_9186.JPG", jpegSiblingName("DSC_9186.NEF"))
    }

    @Test
    fun theSiblingKeepsTheStemEvenWhenItHasDots() {
        assertEquals("DSC_9186 (1).JPG", jpegSiblingName("DSC_9186 (1).NEF"))
    }

    @Test
    fun aJpegHasNoSiblingOfItsOwn() {
        assertNull(jpegSiblingName("DSC_9186.JPG"))
        assertNull(jpegSiblingName("DSC_9186.jpeg"))
    }

    @Test
    fun aNameWithoutAnExtensionCannotProduceOne() {
        assertNull(jpegSiblingName("DSC_9186"))
        assertNull(jpegSiblingName(""))
    }
}
