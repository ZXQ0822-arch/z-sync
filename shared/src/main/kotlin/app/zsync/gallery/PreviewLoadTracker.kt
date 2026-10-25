package app.zsync.gallery

/**
 * Counts preview loads that are in flight so the photo grid can tell whether thumbnails are still
 * arriving. A counter, rather than a boolean, keeps overlapping loads correct: the last one to
 * finish clears the flag, not the first.
 *
 * Every [begin] must be paired with an [end] (normally in a `finally`), which is what stops the
 * "previews still loading" gate from sticking forever.
 */
class PreviewLoadTracker {
    private var active = 0

    val isLoading: Boolean
        get() = active > 0

    fun begin() {
        active += 1
    }

    fun end() {
        if (active > 0) active -= 1
    }

    fun reset() {
        active = 0
    }
}
