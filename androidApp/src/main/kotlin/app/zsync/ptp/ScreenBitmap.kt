package app.zsync.ptp

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal fun decodeThumb(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (oom: OutOfMemoryError) {
        null
    }
}

internal fun decodeForScreen(bytes: ByteArray, maxEdge: Int = 4096): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        val edge = maxEdge.coerceAtLeast(1)
        while (width / sample > edge || height / sample > edge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (oom: OutOfMemoryError) {
        null
    }
}
