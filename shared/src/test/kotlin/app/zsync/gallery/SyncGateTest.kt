package app.zsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncGateTest {
    @Test
    fun anIdleGridLetsTheSyncStart() {
        assertNull(syncBlockReason(saving = false, previewsLoading = false))
    }

    @Test
    fun previewsStillLoadingBlocksWithTheWaitMessage() {
        assertEquals(
            PREVIEWS_LOADING_NOTICE,
            syncBlockReason(saving = false, previewsLoading = true),
        )
    }

    @Test
    fun aRunningSaveIsSilentBecauseItStartsNothing() {
        assertNull(syncBlockReason(saving = true, previewsLoading = false))
        assertNull(syncBlockReason(saving = true, previewsLoading = true))
    }

    @Test
    fun theWaitMessageTellsThePersonWhatToDo() {
        assertEquals("缩略图仍在加载，请等待预览照片加载完成", PREVIEWS_LOADING_NOTICE)
    }
}
