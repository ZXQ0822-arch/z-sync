package app.zsync.gallery

enum class MediaCollection {
    Images,
    Video,
}

fun mediaCollection(format: PhotoFormat): MediaCollection = when (format) {
    PhotoFormat.JPEG, PhotoFormat.NEF -> MediaCollection.Images
    PhotoFormat.MOV, PhotoFormat.MP4 -> MediaCollection.Video
}

fun mediaMime(format: PhotoFormat): String = when (format) {
    PhotoFormat.JPEG -> "image/jpeg"
    PhotoFormat.NEF -> "image/x-nikon-nef"
    PhotoFormat.MOV -> videoMime(format)!!
    PhotoFormat.MP4 -> videoMime(format)!!
}

fun mediaFolder(format: PhotoFormat): String = when (mediaCollection(format)) {
    MediaCollection.Images -> "Pictures/Z-Sync"
    MediaCollection.Video -> "Movies/Z-Sync"
}

/**
 * The name that holds the JPEG sibling of [name], or null when [name] is already a JPEG.
 *
 * The download history can only draw a saved photo when it can read a JPEG: Android's decoder
 * cannot open a NEF. So for a RAW row the album is searched for `DSC_9186.JPG` next to
 * `DSC_9186.NEF`. Returning null here means "no sibling is possible", which is different from
 * "the sibling exists but is not in the album yet".
 */
fun jpegSiblingName(name: String): String? {
    val lower = name.lowercase()
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return null
    val stem = name.substringBeforeLast('.', "")
    if (stem.isEmpty() || stem == name) return null
    return "$stem.JPG"
}

data class StoredMedia(
    val name: String,
    val bytes: Long,
    val mime: String,
    val collection: MediaCollection,
)

/**
 * Whether [stored] is the same file as [file].
 *
 * MediaStore renames a saved file to `NAME (1).EXT` when a file with that name already exists, so a
 * file the app saved earlier may not carry its original name. Only the ` (N)` suffix is tolerated;
 * any other name difference means a different file.
 */
fun sameSavedFile(file: CameraFile, stored: StoredMedia): Boolean {
    return sameSavedName(stored.name, file.name) &&
        stored.bytes == file.bytes &&
        stored.mime == mediaMime(file.format) &&
        stored.collection == mediaCollection(file.format)
}

fun sameSavedName(stored: String, wanted: String): Boolean {
    if (stored == wanted) return true
    val dot = wanted.lastIndexOf('.')
    if (dot <= 0) return false
    val stem = wanted.substring(0, dot)
    val ext = wanted.substring(dot + 1)
    if (!stored.endsWith(".$ext", ignoreCase = true)) return false
    val storedStem = stored.substring(0, stored.length - ext.length - 1)
    if (storedStem == stem) return true
    return storedStem.startsWith("$stem (") && storedStem.endsWith(")")
}
