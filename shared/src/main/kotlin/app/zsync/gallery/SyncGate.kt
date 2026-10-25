package app.zsync.gallery

const val PREVIEWS_LOADING_NOTICE = "缩略图仍在加载，请等待预览照片加载完成"

/**
 * Returns the message to show when a sync must not start, or null when it may go ahead.
 *
 * Syncing while the grid is still fetching previews reads as "nothing happened": the selection is
 * still growing and the download competes with the thumbnail stream. While a save is already
 * running the button is simply a no-op, so there is nothing to say.
 */
fun syncBlockReason(saving: Boolean, previewsLoading: Boolean): String? = when {
    saving -> null
    previewsLoading -> PREVIEWS_LOADING_NOTICE
    else -> null
}
