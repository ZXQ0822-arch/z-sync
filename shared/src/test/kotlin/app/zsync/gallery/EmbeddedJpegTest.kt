package app.zsync.gallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/**
 * Builds the smallest TIFF that a NEF-like file has: IFD0 pointing at one SubIFD, and that SubIFD
 * carrying a JPEG offset and length. Enough to exercise the walk without a real 29 MB file.
 */
private fun tiff(
    order: ByteOrder,
    jpegAt: Array<Pair<Long, Int>>,
    nested: Boolean,
): ByteArray {
    val size = 4096
    val buffer = ByteBuffer.allocate(size).order(order)
    if (order == ByteOrder.LITTLE_ENDIAN) {
        buffer.put('I'.code.toByte()).put('I'.code.toByte())
    } else {
        buffer.put('M'.code.toByte()).put('M'.code.toByte())
    }
    buffer.putShort(42)
    val ifd0 = 8
    buffer.putInt(ifd0)
    val sub = 64
    // IFD0: one entry (SubIFDs -> `sub`), then no next IFD.
    buffer.position(ifd0)
    if (nested) {
        buffer.putShort(1)
        buffer.putShort(0x014A.toShort()).putShort(4).putInt(1).putInt(sub)
        buffer.putInt(0)
    } else {
        // No nesting: IFD0 itself carries the JPEG tags.
        buffer.putShort((jpegAt.size * 2).toShort())
        jpegAt.forEach { (offset, length) ->
            buffer.putShort(0x0201.toShort()).putShort(4).putInt(1).putInt(offset.toInt())
            buffer.putShort(0x0202.toShort()).putShort(4).putInt(1).putInt(length)
        }
        buffer.putInt(0)
    }
    if (nested) {
        buffer.position(sub)
        buffer.putShort((jpegAt.size * 2).toShort())
        jpegAt.forEach { (offset, length) ->
            buffer.putShort(0x0201.toShort()).putShort(4).putInt(1).putInt(offset.toInt())
            buffer.putShort(0x0202.toShort()).putShort(4).putInt(1).putInt(length)
        }
        buffer.putInt(0)
    }
    return buffer.array()
}

private fun source(bytes: ByteArray) = ByteSource { offset, length ->
    val from = offset.toInt()
    if (from >= bytes.size) ByteArray(0)
    else bytes.copyOfRange(from, minOf(bytes.size, from + length))
}

class EmbeddedJpegTest {
    @Test
    fun findsThePreviewThroughASubIfd() {
        val bytes = tiff(ByteOrder.LITTLE_ENDIAN, arrayOf(1500L to 900), nested = true)

        val found = largestEmbeddedJpeg(source(bytes))

        assertEquals(EmbeddedJpeg(1500L, 900), found)
    }

    @Test
    fun keepsTheBiggestWhenSeveralPreviewsExist() {
        val bytes = tiff(
            ByteOrder.BIG_ENDIAN,
            arrayOf(1500L to 300, 1500L to 2400, 1500L to 120),
            nested = true,
        )

        assertEquals(EmbeddedJpeg(1500L, 2400), largestEmbeddedJpeg(source(bytes)))
    }

    @Test
    fun readsTheTagsOffIfd0Itself() {
        val bytes = tiff(ByteOrder.BIG_ENDIAN, arrayOf(2048L to 640), nested = false)

        assertEquals(EmbeddedJpeg(2048L, 640), largestEmbeddedJpeg(source(bytes)))
    }

    @Test
    fun aFileThatIsNotTiffHasNoPreview() {
        assertNull(largestEmbeddedJpeg(source("not a tiff at all".toByteArray())))
    }

    @Test
    fun aTiffWithoutJpegTagsHasNoPreview() {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buffer.put('M'.code.toByte()).put('M'.code.toByte()).putShort(42).putInt(8)
        buffer.position(8)
        buffer.putShort(0).putInt(0)

        assertNull(largestEmbeddedJpeg(source(buffer.array())))
    }

    @Test
    fun theSourceIsAskedForSmallReadsOnly() {
        val bytes = tiff(ByteOrder.LITTLE_ENDIAN, arrayOf(1500L to 900), nested = true)
        var biggest = 0
        val limited = ByteSource { offset, length ->
            biggest = maxOf(biggest, length)
            val from = offset.toInt()
            if (from >= bytes.size) ByteArray(0)
            else bytes.copyOfRange(from, minOf(bytes.size, from + length))
        }

        largestEmbeddedJpeg(limited)

        // Never a whole-file read: the point is not to pull a 29 MB RAW into memory.
        assertEquals(true, biggest <= 8 * 1024)
    }
}
