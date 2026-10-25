package app.zsync.gallery

const val PAGE_SIZE = 10

fun nextLoadedCount(loaded: Int, total: Int, pageSize: Int = PAGE_SIZE): Int {
    require(pageSize > 0)
    return (loaded + pageSize).coerceAtMost(total).coerceAtLeast(0)
}

fun shouldLoadMore(lastVisibleIndex: Int, loaded: Int, total: Int, threshold: Int = 3): Boolean {
    return loaded < total && lastVisibleIndex >= loaded - threshold
}

fun showTailSpinner(firstVisibleIndex: Int, lastVisibleIndex: Int, loaded: Int, total: Int): Boolean {
    if (firstVisibleIndex <= 0) return false
    return shouldLoadMore(lastVisibleIndex, loaded, total)
}
