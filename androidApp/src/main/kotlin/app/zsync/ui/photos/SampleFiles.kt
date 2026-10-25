package app.zsync.ui.photos

import app.zsync.gallery.CameraFile
import app.zsync.gallery.PhotoFormat

fun sampleFiles(): List<CameraFile> {
    val files = mutableListOf<CameraFile>()
    var handle = 1L
    for (shot in 101..125) {
        val key = "DSC_0$shot"
        val locked = shot == 102
        files += CameraFile(handle++, "$key.JPG", 3_200_000L + shot * 21_000L, PhotoFormat.JPEG, key, locked)
        if (shot % 3 == 1) {
            files += CameraFile(handle++, "$key.NEF", 21_000_000L + shot * 9_000L, PhotoFormat.NEF, key, locked)
        }
    }
    return files
}
