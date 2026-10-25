package app.zsync.gallery

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class PhotoFormat {
    JPEG,
    NEF,
    MOV,
    MP4,
}

enum class MediaFilter(val label: String) {
    All("全部"),
    Jpeg("JPEG"),
    Raw("RAW / NEF"),
    Mov("MOV"),
    Mp4("MP4"),
}

const val EMPTY_FILTER = "没有这种格式"

fun userMediaFormat(formatCode: Int, name: String): PhotoFormat? {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension == "ndf") return null
    val byCode = when (formatCode) {
        0x3801 -> PhotoFormat.JPEG
        0x300D -> PhotoFormat.MOV
        0xB982 -> PhotoFormat.MP4
        else -> null
    }
    if (byCode != null) return byCode
    return when (extension) {
        "jpg", "jpeg" -> PhotoFormat.JPEG
        "nef" -> PhotoFormat.NEF
        "mov" -> PhotoFormat.MOV
        "mp4" -> PhotoFormat.MP4
        else -> null
    }
}

fun visibleMedia(files: List<CameraFile>, filter: MediaFilter): List<CameraFile> {
    return files.filter { file ->
        when (filter) {
            MediaFilter.All -> true
            MediaFilter.Jpeg -> file.format == PhotoFormat.JPEG
            MediaFilter.Raw -> file.format == PhotoFormat.NEF
            MediaFilter.Mov -> file.format == PhotoFormat.MOV
            MediaFilter.Mp4 -> file.format == PhotoFormat.MP4
        }
    }
}

fun downloadsOriginal(format: PhotoFormat): Boolean =
    format == PhotoFormat.JPEG || format == PhotoFormat.NEF

fun selectionAfterFilter(selected: Set<Long>, visible: List<CameraFile>): Set<Long> = selected

enum class PlaybackKind {
    Photo,
    InApp,
    System,
    NeedsSync,
    Failed,
}

const val VIDEO_PLACEHOLDER = "视频"
const val VIDEO_NEEDS_SYNC = "同步到相册后可以播放"
const val OPEN_WITH_SYSTEM = "用系统播放器打开"
const val VIDEO_OPEN_FAILED = "这部视频打不开"

fun videoMime(format: PhotoFormat): String? = when (format) {
    PhotoFormat.MOV -> "video/quicktime"
    PhotoFormat.MP4 -> "video/mp4"
    else -> null
}

fun playbackKind(format: PhotoFormat, savedOnPhone: Boolean, inAppFailed: Boolean, systemFailed: Boolean): PlaybackKind {
    if (format == PhotoFormat.JPEG || format == PhotoFormat.NEF) return PlaybackKind.Photo
    if (!savedOnPhone) return PlaybackKind.NeedsSync
    if (systemFailed) return PlaybackKind.Failed
    if (inAppFailed) return PlaybackKind.System
    return PlaybackKind.InApp
}

fun statusAfterDelete(remaining: Int, current: String): String =
    if (remaining == 0) "相机里没有照片" else current

data class CameraFile(
    val handle: Long,
    val name: String,
    val bytes: Long,
    val format: PhotoFormat,
    val burstKey: String,
    val locked: Boolean = false,
    val capturedLocalAt: LocalDateTime? = null,
    val sequence: Long = 0,
) {
    val captureDate: LocalDate?
        get() = cameraDateKey()
}

const val UNRECORDED_DATE = "日期未记录"

fun CameraFile.cameraDateKey(): LocalDate? = capturedLocalAt?.toLocalDate()

fun dateHeading(key: LocalDate?, includeYear: Boolean = false): String {
    if (key == null) return UNRECORDED_DATE
    val day = "${key.monthValue}月${key.dayOfMonth}日"
    return if (includeYear) "${key.year}年$day" else day
}

fun CameraFile.capturedAtMillis(zoneId: ZoneId): Long? =
    capturedLocalAt?.atZone(zoneId)?.toInstant()?.toEpochMilli()

enum class PhotoOrder {
    OLDEST,
    NEWEST,
}

fun sortedPhotos(files: List<CameraFile>, order: PhotoOrder): List<CameraFile> {
    val chronological = files.sortedWith(
        compareBy<CameraFile> { it.capturedLocalAt == null }
            .thenBy { it.capturedLocalAt ?: LocalDateTime.MIN }
            .thenBy { it.orderFallback() },
    )
    return if (order == PhotoOrder.OLDEST) chronological else chronological.asReversed()
}

private fun CameraFile.orderFallback(): Long = if (sequence != 0L) sequence else handle

enum class PhotoLayout {
    GRID,
    LIST,
}

enum class GridCount(val columns: Int, val rows: Int) {
    FOUR(2, 2),
    SIX(2, 3),
    NINE(3, 3),
}

data class DeletePlan(
    val remove: Set<Long>,
    val keptLocked: List<String>,
)

data class SaveBatch(
    val picks: List<CameraFile>,
    val selecting: Boolean,
)

fun saveBatch(files: List<CameraFile>, selected: Set<Long>): SaveBatch? {
    if (selected.isEmpty()) return null
    val picks = files.filter { it.handle in selected }
    if (picks.isEmpty()) return null
    return SaveBatch(picks = picks, selecting = false)
}

fun planDelete(files: List<CameraFile>, handles: Set<Long>): DeletePlan {
    val keys = files.filter { it.handle in handles }.map { it.burstKey }.toSet()
    val group = files.filter { it.burstKey in keys }
    val lockedKeys = group.filter { it.locked }.map { it.burstKey }.toSet()
    return DeletePlan(
        remove = group.filter { it.burstKey !in lockedKeys }.map { it.handle }.toSet(),
        keptLocked = group.filter { it.locked }.map { it.name },
    )
}

fun formatBytes(bytes: Long): String {
    require(bytes >= 0)
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
    return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
}
