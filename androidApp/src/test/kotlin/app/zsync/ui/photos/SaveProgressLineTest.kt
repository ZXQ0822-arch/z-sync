package app.zsync.ui.photos

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class SaveProgressLineTest {
    @Test
    fun theFirstStoredPhotoStartsTheCounter() {
        assertEquals("正在保存 1/3 张照片", saveProgressLine(done = 1, total = 3))
    }

    @Test
    fun theLastStoredPhotoEndsOnTheTotal() {
        assertEquals("正在保存 3/3 张照片", saveProgressLine(done = 3, total = 3))
    }

    @Test
    fun nothingIsSaidBeforeTheFirstPhotoLands() {
        // Otherwise the headline flips from "正在保存 3 张照片" to "0/3", which reads backwards.
        assertNull(saveProgressLine(done = 0, total = 3))
    }

    @Test
    fun anEmptyBatchSaysNothing() {
        assertNull(saveProgressLine(done = 0, total = 0))
    }

    @Test
    fun aSinglePhotoStillReportsItsOneStep() {
        assertEquals("正在保存 1/1 张照片", saveProgressLine(done = 1, total = 1))
    }
}
