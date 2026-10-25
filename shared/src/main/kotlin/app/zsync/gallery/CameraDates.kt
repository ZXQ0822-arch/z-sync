package app.zsync.gallery

import java.time.LocalDate

data class CameraDateGroup(
    val key: LocalDate?,
    val heading: String,
    val items: List<CameraFile>,
)

sealed interface CatalogRow {
    data class Heading(val key: LocalDate?, val text: String) : CatalogRow

    data class Media(val file: CameraFile) : CatalogRow
}

fun groupByCameraDate(files: List<CameraFile>, order: PhotoOrder): List<CameraDateGroup> {
    val sorted = sortedPhotos(files, order)
    val includeYear = sorted.mapNotNull { it.cameraDateKey()?.year }.distinct().size > 1
    val groups = mutableListOf<CameraDateGroup>()
    sorted.forEach { file ->
        val key = file.cameraDateKey()
        val last = groups.lastOrNull()
        if (last != null && last.key == key) {
            groups[groups.lastIndex] = last.copy(items = last.items + file)
        } else {
            groups += CameraDateGroup(key, dateHeading(key, includeYear), listOf(file))
        }
    }
    return groups
}

fun catalogRows(groups: List<CameraDateGroup>): List<CatalogRow> {
    return groups.flatMap { group ->
        listOf(CatalogRow.Heading(group.key, group.heading)) + group.items.map { CatalogRow.Media(it) }
    }
}

fun mediaCount(rows: List<CatalogRow>): Int = rows.count { it is CatalogRow.Media }

fun mediaPosition(rows: List<CatalogRow>, lazyIndex: Int): Int {
    if (rows.isEmpty() || lazyIndex < 0) return 0
    var seen = 0
    val end = lazyIndex.coerceAtMost(rows.lastIndex)
    for (index in 0..end) {
        if (rows[index] is CatalogRow.Media) seen++
    }
    return (seen - 1).coerceAtLeast(0)
}
