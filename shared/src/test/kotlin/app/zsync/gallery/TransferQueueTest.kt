package app.zsync.gallery

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferQueueTest {
    @Test
    fun theFirstChunkIsOneMebibyte() {
        assertEquals(1_048_576L, TRANSFER_CHUNK_BYTES)
        assertEquals(TRANSFER_CHUNK_BYTES, TransferQueue().chunkBytes)
    }

    @Test
    fun syncKeepsAnOffsetForEveryFormatAndNotTheBytes() {
        listOf(TransferFormat.JPEG, TransferFormat.NEF, TransferFormat.MOV, TransferFormat.MP4).forEach { format ->
            val request = file("CLIP.$format", 12, format)
            val started = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(request))
            val mid = deliver(started.queue, request.identity, (0 until 8).map { it.toByte() })
            assertEquals(8L, mid.queue.received.getValue(identityKey(request.identity)))
            assertTrue(mid.queue.temps.isEmpty())
            assertTrue(mid.gallery.isEmpty())
        }
    }

    @Test
    fun everyListedFormatEntersTheSameQueue() {
        val queue = listOf(TransferFormat.JPEG, TransferFormat.NEF, TransferFormat.MOV, TransferFormat.MP4)
            .fold(TransferQueue(chunkBytes = 8)) { current, format ->
                reduce(current, QueueEvent.Add(file("DSC_$format.ext", 4, format))).queue
            }
        assertEquals(
            listOf(TransferFormat.JPEG, TransferFormat.NEF, TransferFormat.MOV, TransferFormat.MP4),
            queue.items.map { it.format },
        )
    }

    @Test
    fun previewBeatsSyncAndThumbsWhileOnlyOneCommandRuns() {
        val preview = file("preview.jpg", 20, TransferFormat.JPEG, TransferKind.Preview)
        val sync = file("sync.nef", 20, TransferFormat.NEF, TransferKind.Sync)
        val visible = file("visible.jpg", 20, TransferFormat.JPEG, TransferKind.VisibleThumb)
        val prefetch = file("prefetch.jpg", 20, TransferFormat.JPEG, TransferKind.PrefetchThumb)
        var queue = TransferQueue(chunkBytes = 8, selecting = true)
        listOf(prefetch, visible, sync).forEach { queue = reduce(queue, QueueEvent.Add(it)).queue.copy(inFlight = null) }
        val started = reduce(queue, QueueEvent.Add(preview))
        assertEquals(preview.identity, started.read?.identity)
        assertEquals(0L, started.read?.offset)
        assertEquals(8L, started.read?.count)
        val extra = reduce(started.queue, QueueEvent.Add(file("other.jpg", 20, TransferFormat.JPEG, TransferKind.Preview)))
        assertNull(extra.read)
        assertEquals(started.queue.inFlight, extra.queue.inFlight)
    }

    @Test
    fun syncLeavesSelectionBeforeTheFirstChunkReturns() {
        val paused = TransferQueue(chunkBytes = 8, selecting = true, paused = true)
        val started = reduce(paused, QueueEvent.BeginSync(listOf(file("A.jpg", 10, TransferFormat.JPEG))))
        assertFalse(started.queue.selecting)
        assertNull(started.read)
        val resumed = reduce(started.queue, QueueEvent.Resume)
        assertFalse(resumed.queue.selecting)
        assertEquals(0L, resumed.read?.offset)
    }

    @Test
    fun aPartialChunkResumesFromTheSafeOffsetAndMatchesTheOriginalBytes() {
        val original = (0 until 20).map { it.toByte() }
        val identity = file("DSC_0001.jpg", original.size, TransferFormat.JPEG).identity
        val first = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(file("DSC_0001.jpg", original.size, TransferFormat.JPEG)))
        val atEight = deliver(first.queue, identity, original.subList(0, 8))
        assertEquals(8L, atEight.read?.offset)
        val atSixteen = deliver(atEight.queue, identity, original.subList(8, 16))
        assertEquals(16L, atSixteen.read?.offset)
        assertEquals(4L, atSixteen.read?.count)
        val paused = reduce(atSixteen.queue, QueueEvent.Pause)
        val dropped = deliver(paused.queue, identity, original.subList(16, 19))
        assertNull(dropped.read)
        assertEquals(19L, dropped.queue.received.getValue(identityKey(identity)))
        assertTrue(dropped.queue.temps.isEmpty())
        assertTrue(dropped.gallery.isEmpty())
        val resumed = reduce(dropped.queue, QueueEvent.Resume)
        assertEquals(19L, resumed.read?.offset)
        assertEquals(1L, resumed.read?.count)
        val done = deliver(resumed.queue, identity, original.subList(19, 20))
        assertEquals(listOf(identity), done.gallery.map { it.identity })
        assertEquals(emptyList(), done.gallery.single().bytes)
        assertNull(done.queue.temps[identityKey(identity)])
        assertNull(done.queue.received[identityKey(identity)])
    }

    @Test
    fun aShortCameraReplyAdvancesByTheBytesActuallyReturned() {
        val original = (0 until 10).map { it.toByte() }
        val identity = file("DSC_0002.nef", 10, TransferFormat.NEF).identity
        val started = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(file("DSC_0002.nef", 10, TransferFormat.NEF)))
        val short = deliver(started.queue, identity, original.subList(0, 3))
        assertEquals(3L, short.read?.offset)
        assertEquals(7L, short.read?.count)
        val empty = deliver(short.queue, identity, emptyList())
        assertNull(empty.read)
        assertEquals(3L, empty.queue.received.getValue(identityKey(identity)))
        assertTrue(empty.queue.temps.isEmpty())
        val resumed = reduce(empty.queue, QueueEvent.Resume)
        assertEquals(3L, resumed.read?.offset)
    }

    @Test
    fun bytesPastTheObjectEndDoNotGetCommittedEarly() {
        val identity = file("DSC_0003.jpg", 10, TransferFormat.JPEG).identity
        val started = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(file("DSC_0003.jpg", 10, TransferFormat.JPEG)))
        val first = deliver(started.queue, identity, (0 until 8).map { it.toByte() })
        val finished = deliver(first.queue, identity, (8 until 18).map { it.toByte() })
        assertEquals(listOf(identity), finished.gallery.map { it.identity })
        assertEquals(emptyList(), finished.gallery.single().bytes)
        assertTrue(identityKey(identity) in finished.queue.committed)
        assertNull(finished.queue.received[identityKey(identity)])
    }

    @Test
    fun pauseKeepsTheOffsetAndResumeContinuesTheSameObject() {
        val identity = file("DSC_0004.jpg", 16, TransferFormat.JPEG).identity
        val started = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(file("DSC_0004.jpg", 16, TransferFormat.JPEG)))
        val paused = reduce(started.queue, QueueEvent.Pause)
        assertNull(paused.read)
        assertEquals(started.queue.inFlight, paused.queue.inFlight)
        val wrote = deliver(paused.queue, identity, (0 until 8).map { it.toByte() })
        assertNull(wrote.read)
        assertTrue(wrote.gallery.isEmpty())
        val resumed = reduce(wrote.queue, QueueEvent.Resume)
        assertEquals(8L, resumed.read?.offset)
        assertEquals(identity, resumed.read?.identity)
    }

    @Test
    fun savedAndSkippedPhotosAreNotReadAgainEvenWhenTheHandleChanges() {
        val saved = file("DSC_0005.jpg", 10, TransferFormat.JPEG, handle = 1)
        val marked = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Saved(saved.identity))
        val again = reduce(marked.queue, QueueEvent.Add(saved.copy(handle = 99)))
        assertNull(again.read)
        assertEquals(99L, again.queue.items.single().handle)

        val skipped = file("DSC_0006.jpg", 10, TransferFormat.JPEG)
        val quiet = reduce(reduce(TransferQueue(chunkBytes = 8), QueueEvent.Skip(skipped.identity)).queue, QueueEvent.Add(skipped.copy(kind = TransferKind.PrefetchThumb)))
        assertNull(quiet.read)
        val preview = reduce(quiet.queue, QueueEvent.Add(skipped.copy(kind = TransferKind.Preview, handle = 7)))
        assertEquals(skipped.identity, preview.read?.identity)
        assertEquals(7L, preview.queue.items.single().handle)
    }

    @Test
    fun cancelAndRestartLeavePartialBytesOutOfTheGallery() {
        val request = file("DSC_0007.jpg", 20, TransferFormat.JPEG)
        val started = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(request))
        val partial = deliver(started.queue, request.identity, (0 until 8).map { it.toByte() })
        val cancelled = reduce(partial.queue, QueueEvent.Cancel)
        assertEquals(listOf(request.identity), cancelled.discarded)
        assertTrue(cancelled.gallery.isEmpty())
        assertTrue(cancelled.queue.temps.isEmpty())
        assertTrue(cancelled.queue.items.isEmpty())

        val stored = StoredTransfer(request, temp = (0 until 8).map { it.toByte() }, committed = false)
        val restored = restore(listOf(stored), chunkBytes = 8)
        assertTrue(restored.gallery.isEmpty())
        assertEquals(8L, restored.read?.offset)
        assertEquals(8L, restored.queue.received.getValue(identityKey(request.identity)))
        assertTrue(restored.queue.temps.isEmpty())

        val complete = StoredTransfer(request, temp = (0 until 20).map { it.toByte() }, committed = false)
        val finished = restore(listOf(complete), chunkBytes = 8)
        assertEquals(emptyList(), finished.gallery.single().bytes)
        assertEquals(listOf(request.identity), finished.gallery.map { it.identity })
        assertNull(finished.read)
        assertTrue(finished.queue.temps.isEmpty())

        val already = StoredTransfer(request, committed = true)
        val idle = restore(listOf(already), chunkBytes = 8)
        assertTrue(idle.gallery.isEmpty())
        assertNull(idle.read)
        assertTrue(identityKey(request.identity) in idle.queue.committed)
    }

    @Test
    fun aDeliveredChunkForAnotherObjectDoesNotMoveTheSafeOffset() {
        val request = file("DSC_0008.jpg", 10, TransferFormat.JPEG)
        val started = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(request))
        val other = file("DSC_0009.jpg", 10, TransferFormat.JPEG).identity
        val ignored = reduce(started.queue, QueueEvent.Delivered(other, listOf(1)))
        assertEquals(started.queue, ignored.queue)
        assertTrue(ignored.gallery.isEmpty())
    }

    @Test
    fun aBrokenChunkKeepsTheSafeBytesAndTheNextPullContinues() = runBlocking {
        val original = (0 until 20).map { it.toByte() }
        val request = file("DSC_0010.jpg", 20, TransferFormat.JPEG)
        val queued = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Add(request)).queue
        var calls = 0
        val stopped = pull(queued) { chunk ->
            calls += 1
            if (calls == 2) throw IllegalStateException("closed")
            original.subList(chunk.offset.toInt(), (chunk.offset + chunk.count).toInt())
        }
        assertFalse(stopped.finished)
        assertTrue(stopped.gallery.isEmpty())
        assertNull(stopped.queue.inFlight)
        assertEquals(8L, stopped.queue.received.getValue(identityKey(request.identity)))
        assertTrue(stopped.queue.temps.isEmpty())

        val offsets = mutableListOf<Long>()
        val done = pull(stopped.queue) { chunk ->
            offsets += chunk.offset
            original.subList(chunk.offset.toInt(), (chunk.offset + chunk.count).toInt())
        }
        assertEquals(listOf(8L, 16L), offsets)
        assertTrue(done.finished)
        assertEquals(emptyList(), done.gallery.single().bytes)
        assertTrue(done.queue.temps.isEmpty())

        val saved = reduce(TransferQueue(chunkBytes = 8), QueueEvent.Saved(request.identity)).queue
        val idle = pull(reduce(saved, QueueEvent.Add(request)).queue) { error("already saved") }
        assertTrue(idle.finished)
        assertTrue(idle.gallery.isEmpty())
    }
}

private fun file(
    name: String,
    bytes: Int,
    format: TransferFormat,
    kind: TransferKind = TransferKind.Sync,
    handle: Long = 1,
    capturedAt: String? = "2026-10-23T13:00",
) = TransferRequest(TransferIdentity(name, bytes.toLong(), capturedAt), format, kind, handle)

private data class Delivered(
    val queue: TransferQueue,
    val read: ChunkRequest?,
    val gallery: List<GalleryWrite>,
)

private fun deliver(queue: TransferQueue, identity: TransferIdentity, bytes: List<Byte>): Delivered {
    val step = reduce(queue, QueueEvent.Delivered(identity, bytes))
    return Delivered(step.queue, step.read, step.gallery)
}

private fun originalOf(stored: StoredTransfer) = stored.temp
