package app.zsync.gallery

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TransferFilesTest {
    @Test
    fun aBreakBeforeAndAfterTheChunkBoundaryResumesTheSameBytes() = runBlocking {
        val original = ByteArray(20) { it.toByte() }
        val before = stage(original, chunkBytes = 8) { call, chunk ->
            if (call == 1) original.copyOfRange(chunk.offset.toInt(), chunk.offset.toInt() + 7) else null
        }
        assertEquals(listOf("temp:7"), before.log)
        assertFalse(before.files.publishable(before.identity))
        assertNull(before.files.source(before.identity))
        val beforeDone = resume(before)
        assertContentEquals(original, beforeDone.bytes)
        assertEquals("gallery", beforeDone.log.last())
        assertTrue(beforeDone.log.dropLast(1).all { it.startsWith("temp:") })
        before.root.deleteRecursively()

        val afterBreak = stage(original, chunkBytes = 8) { call, chunk ->
            if (call == 2) null else original.copyOfRange(chunk.offset.toInt(), (chunk.offset + chunk.count).toInt())
        }
        assertEquals(8L, afterBreak.files.length(afterBreak.identity))
        assertEquals(listOf("temp:8"), afterBreak.log)
        val afterDone = resume(afterBreak)
        assertContentEquals(original, afterDone.bytes)
        assertEquals(listOf("temp:8", "temp:16", "temp:20", "gallery"), afterDone.log)
        afterBreak.root.deleteRecursively()
    }

    @Test
    fun aTornTailIsCutBackToTheSafeOffsetAfterRestart() {
        val identity = file("DSC_0100.jpg", 20).identity
        val root = Files.createTempDirectory("z-sync-transfer").toFile()
        try {
            val first = TransferFiles(root)
            assertEquals(8L, first.append(identity, ByteArray(8) { it.toByte() }))
            first.source(identity).let { assertNull(it) }
            val part = root.listFiles { file -> file.name.endsWith(".part") }!!.single()
            part.appendBytes(byteArrayOf(9, 9, 9))
            val restarted = TransferFiles(root)
            assertEquals(8L, restarted.length(identity))
            assertContentEquals(ByteArray(8) { it.toByte() }, restarted.remembered(identity))
            assertFalse(restarted.publishable(identity))
            restarted.discardIncomplete()
            assertNull(restarted.remembered(identity))
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") || it.name.endsWith(".meta") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aCompleteTempPublishesOnceAndAPartialNeverDoes() {
        val identity = file("DSC_0101.NEF", 4, TransferFormat.NEF).identity
        val root = Files.createTempDirectory("z-sync-transfer").toFile()
        try {
            val files = TransferFiles(root)
            val log = mutableListOf<String>()
            files.append(identity, byteArrayOf(1, 2))
            log += "temp:${files.length(identity)}"
            if (files.publishable(identity)) log += "gallery"
            assertEquals(listOf("temp:2"), log)
            files.append(identity, byteArrayOf(3, 4))
            log += "temp:${files.length(identity)}"
            val source = files.source(identity)
            if (source != null) log += "gallery"
            assertEquals(listOf("temp:2", "temp:4", "gallery"), log)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), source!!.readBytes())
            files.discard(identity)
            assertNull(files.source(identity))
        } finally {
            root.deleteRecursively()
        }
    }
}

private class Staged(
    val files: TransferFiles,
    val identity: TransferIdentity,
    val queue: TransferQueue,
    val log: List<String>,
    val root: java.io.File,
    val bytes: ByteArray = ByteArray(0),
)

private fun file(name: String, bytes: Int, format: TransferFormat = TransferFormat.JPEG) =
    TransferRequest(TransferIdentity(name, bytes.toLong(), "2026-10-23T13:00"), format, TransferKind.Sync, 1)

private fun stage(
    original: ByteArray,
    chunkBytes: Long,
    read: (call: Int, chunk: ChunkRequest) -> ByteArray?,
): Staged = runBlocking {
    val root = Files.createTempDirectory("z-sync-transfer").toFile()
    val files = TransferFiles(root)
    val request = file("DSC_0099.jpg", original.size)
    val log = mutableListOf<String>()
    var calls = 0
    val pulled = pull(reduce(TransferQueue(chunkBytes = chunkBytes), QueueEvent.Add(request)).queue) { chunk ->
        calls += 1
        val slice = read(calls, chunk) ?: throw java.io.IOException("closed")
        files.append(chunk.identity, slice)
        log += "temp:${files.length(chunk.identity)}"
        slice.toList()
    }
    Staged(files, request.identity, pulled.queue, log.toList(), root)
}

private fun resume(staged: Staged): Staged = runBlocking {
    val log = staged.log.toMutableList()
    val pulled = pull(staged.queue) { chunk ->
        val count = chunk.count.toInt()
        val slice = ByteArray(count) { index -> (chunk.offset + index).toByte() }
        staged.files.append(chunk.identity, slice)
        log += "temp:${staged.files.length(chunk.identity)}"
        slice.toList()
    }
    val source = staged.files.source(staged.identity)
    check(source != null)
    log += "gallery"
    val bytes = source.readBytes()
    assertTrue(pulled.finished)
    assertEquals(emptyList(), pulled.gallery.single().bytes)
    Staged(staged.files, staged.identity, pulled.queue, log, staged.root, bytes)
}
