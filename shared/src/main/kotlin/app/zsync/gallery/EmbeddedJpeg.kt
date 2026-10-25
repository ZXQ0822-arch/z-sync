package app.zsync.gallery

import java.nio.ByteOrder

/** Random access over a file, so finding a preview never loads a 29 MB RAW to reach it. */
fun interface ByteSource {
    /** Up to [length] bytes at [offset]; fewer when the file ends first. */
    fun read(offset: Long, length: Int): ByteArray
}

/** Where the embedded JPEG preview sits inside a TIFF-based RAW. */
data class EmbeddedJpeg(val offset: Long, val length: Int)

/**
 * Finds the largest JPEG preview inside a TIFF-based RAW file.
 *
 * A NEF is a TIFF container: besides the sensor data it carries a small thumbnail and one or more
 * full-size JPEG previews, stored as a plain JPEG at an offset recorded by the `JPEGInterchangeFormat`
 * (0x0201) and `JPEGInterchangeFormatLength` (0x0202) tags. Reading those tags is how a gallery shows
 * a RAW without a RAW decoder — Android's own decoder refuses a NEF.
 *
 * Walks IFD0, the next-IFD chain and every SubIFD (0x014A), then keeps the biggest JPEG it finds.
 * Returns null for anything that is not a TIFF, or that has no embedded JPEG.
 */
fun largestEmbeddedJpeg(source: ByteSource): EmbeddedJpeg? {
    val header = source.read(0, 8)
    if (header.size < 8) return null
    val order = when {
        header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
        header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
        else -> return null
    }
    if (ushort(order, header, 2) != 42) return null

    val pending = ArrayDeque<Long>()
    val seen = mutableSetOf<Long>()
    pending.addLast(uint(order, header, 4))
    var best: EmbeddedJpeg? = null

    while (pending.isNotEmpty() && seen.size < MAX_IFDS) {
        val ifd = pending.removeFirst()
        if (ifd <= 0L || !seen.add(ifd)) continue
        val countBytes = source.read(ifd, 2)
        if (countBytes.size < 2) continue
        val entries = ushort(order, countBytes, 0)
        if (entries <= 0 || entries > MAX_ENTRIES) continue

        val table = source.read(ifd + 2, entries * 12 + 4)
        if (table.size < entries * 12) continue

        var pendingOffset = -1L
        for (i in 0 until entries) {
            val at = i * 12
            // tag and type are 2-byte SHORTs: reading them as a 4-byte word breaks big-endian files
            val tag = ushort(order, table, at)
            val count = uint(order, table, at + 4)
            val inline = uint(order, table, at + 8)
            when (tag) {
                TAG_JPEG_OFFSET -> pendingOffset = inline.toLong()
                TAG_JPEG_LENGTH -> {
                    // Pair the length with the offset that preceded it, so several previews in one
                    // directory each count instead of only the last.
                    if (pendingOffset > 0 && inline > 0) {
                        val candidate = EmbeddedJpeg(pendingOffset, inline.toInt())
                        if (best == null || candidate.length > best.length) best = candidate
                    }
                    pendingOffset = -1L
                }
                TAG_SUB_IFDS -> if (count in 1..MAX_ENTRIES) {
                    // One offset fits inline; more are stored elsewhere in the file.
                    if (count == 1L) {
                        pending.addLast(inline)
                    } else {
                        val extra = source.read(inline, (count * 4).toInt())
                        var k = 0L
                        while (k + 4 <= extra.size) {
                            pending.addLast(uint(order, extra, k.toInt()))
                            k += 4
                        }
                    }
                }
            }
        }

        val next = if (table.size >= entries * 12 + 4) uint(order, table, entries * 12) else 0L
        if (next > 0L) pending.addLast(next)
    }
    return best
}

private const val TAG_SUB_IFDS = 0x014A
private const val TAG_JPEG_OFFSET = 0x0201
private const val TAG_JPEG_LENGTH = 0x0202
private const val MAX_IFDS = 64
private const val MAX_ENTRIES = 512

private fun ushort(order: ByteOrder, bytes: ByteArray, at: Int): Int {
    if (at < 0 || at + 2 > bytes.size) return 0
    val first = bytes[at].toInt() and 0xFF
    val second = bytes[at + 1].toInt() and 0xFF
    return if (order == ByteOrder.LITTLE_ENDIAN) first or (second shl 8) else (first shl 8) or second
}

private fun uint(order: ByteOrder, bytes: ByteArray, at: Int): Long {
    if (at < 0 || at + 4 > bytes.size) return 0L
    var value = 0L
    if (order == ByteOrder.LITTLE_ENDIAN) {
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    } else {
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    }
    return value
}
