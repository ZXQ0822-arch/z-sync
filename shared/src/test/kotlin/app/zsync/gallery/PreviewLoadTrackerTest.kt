package app.zsync.gallery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewLoadTrackerTest {
    @Test
    fun itIsIdleUntilALoadBegins() {
        val tracker = PreviewLoadTracker()
        assertFalse(tracker.isLoading)
        tracker.begin()
        assertTrue(tracker.isLoading)
    }

    @Test
    fun overlappingLoadsStayLoadingUntilTheLastOneEnds() {
        val tracker = PreviewLoadTracker()
        tracker.begin()
        tracker.begin()
        tracker.end()
        assertTrue(tracker.isLoading, "one load is still running")
        tracker.end()
        assertFalse(tracker.isLoading)
    }

    @Test
    fun anExtraEndDoesNotGoNegative() {
        val tracker = PreviewLoadTracker()
        tracker.end()
        assertFalse(tracker.isLoading)
        tracker.begin()
        assertTrue(tracker.isLoading, "a stray end must not cancel a later load")
    }

    @Test
    fun resetClearsEverything() {
        val tracker = PreviewLoadTracker()
        tracker.begin()
        tracker.begin()
        tracker.reset()
        assertFalse(tracker.isLoading)
        tracker.begin()
        assertTrue(tracker.isLoading)
    }
}
