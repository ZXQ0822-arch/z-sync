package app.zsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagingTest {
    @Test
    fun loadsTenAtATimeAndStopsAtTheEnd() {
        assertEquals(10, nextLoadedCount(loaded = 0, total = 25))
        assertEquals(20, nextLoadedCount(loaded = 10, total = 25))
        assertEquals(25, nextLoadedCount(loaded = 20, total = 25))
        assertEquals(4, nextLoadedCount(loaded = 0, total = 4))
    }

    @Test
    fun asksForMoreOnlyNearTheLastLoadedItem() {
        assertFalse(shouldLoadMore(lastVisibleIndex = 5, loaded = 10, total = 25))
        assertTrue(shouldLoadMore(lastVisibleIndex = 7, loaded = 10, total = 25))
        assertFalse(shouldLoadMore(lastVisibleIndex = 24, loaded = 25, total = 25))
    }

    @Test
    fun theTailSpinnerAppearsAfterScrollingWhileMoreRemain() {
        assertFalse(showTailSpinner(firstVisibleIndex = 0, lastVisibleIndex = 8, loaded = 9, total = 40))
        assertFalse(showTailSpinner(firstVisibleIndex = 0, lastVisibleIndex = 8, loaded = 10, total = 40))
        assertTrue(showTailSpinner(firstVisibleIndex = 1, lastVisibleIndex = 7, loaded = 10, total = 40))
        assertTrue(showTailSpinner(firstVisibleIndex = 1, lastVisibleIndex = 9, loaded = 10, total = 40))
        assertFalse(showTailSpinner(firstVisibleIndex = 1, lastVisibleIndex = 9, loaded = 10, total = 10))
        assertFalse(showTailSpinner(firstVisibleIndex = 0, lastVisibleIndex = 0, loaded = 0, total = 40))
    }
}
