package app.zsync.gallery

const val TRANSFER_CHUNK_BYTES = 1_048_576L

enum class TransferKind {
    Preview,
    Sync,
    VisibleThumb,
    PrefetchThumb,
}

enum class TransferFormat {
    JPEG,
    NEF,
    MOV,
    MP4,
}

data class TransferIdentity(
    val name: String,
    val bytes: Long,
    val capturedAt: String?,
)

data class TransferRequest(
    val identity: TransferIdentity,
    val format: TransferFormat,
    val kind: TransferKind,
    val handle: Long,
)

data class StoredTransfer(
    val request: TransferRequest,
    val temp: List<Byte> = emptyList(),
    val committed: Boolean = false,
)

data class ChunkRequest(
    val identity: TransferIdentity,
    val offset: Long,
    val count: Long,
)

data class TransferQueue(
    val chunkBytes: Long = TRANSFER_CHUNK_BYTES,
    val items: List<TransferRequest> = emptyList(),
    val temps: Map<String, List<Byte>> = emptyMap(),
    val received: Map<String, Long> = emptyMap(),
    val committed: Set<String> = emptySet(),
    val skipped: Set<String> = emptySet(),
    val paused: Boolean = false,
    val selecting: Boolean = false,
    val inFlight: String? = null,
)

sealed interface QueueEvent {
    data class Add(val request: TransferRequest) : QueueEvent

    data class BeginSync(val requests: List<TransferRequest>) : QueueEvent

    data class Saved(val identity: TransferIdentity) : QueueEvent

    data class Skip(val identity: TransferIdentity) : QueueEvent

    data object Pause : QueueEvent

    data object Resume : QueueEvent

    data class Delivered(val identity: TransferIdentity, val bytes: List<Byte>) : QueueEvent

    data object Cancel : QueueEvent
}

data class GalleryWrite(
    val identity: TransferIdentity,
    val bytes: List<Byte>,
)

data class QueueTransition(
    val queue: TransferQueue,
    val read: ChunkRequest? = null,
    val gallery: List<GalleryWrite> = emptyList(),
    val discarded: List<TransferIdentity> = emptyList(),
)

fun identityKey(identity: TransferIdentity): String {
    return "${identity.name}\u0000${identity.bytes}\u0000${identity.capturedAt.orEmpty()}"
}

fun restore(records: List<StoredTransfer>, chunkBytes: Long = TRANSFER_CHUNK_BYTES): QueueTransition {
    val items = mutableListOf<TransferRequest>()
    val temps = mutableMapOf<String, List<Byte>>()
    val received = mutableMapOf<String, Long>()
    val committed = mutableSetOf<String>()
    val gallery = mutableListOf<GalleryWrite>()
    records.forEach { record ->
        val key = identityKey(record.request.identity)
        items += record.request
        val complete = record.temp.size.toLong() == record.request.identity.bytes
        if (record.committed || complete) {
            committed += key
            if (!record.committed && complete) {
                gallery += GalleryWrite(
                    record.request.identity,
                    if (record.request.kind == TransferKind.Sync) emptyList() else record.temp,
                )
            }
        } else if (record.request.kind == TransferKind.Sync) {
            if (record.temp.isNotEmpty()) received[key] = record.temp.size.toLong()
        } else {
            temps[key] = record.temp
        }
    }
    val pumped = pump(
        TransferQueue(
            chunkBytes = chunkBytes,
            items = items,
            temps = temps,
            received = received,
            committed = committed,
        ),
    )
    return pumped.copy(gallery = gallery + pumped.gallery)
}

data class PullResult(
    val queue: TransferQueue,
    val gallery: List<GalleryWrite>,
    val finished: Boolean,
)

suspend fun pull(queue: TransferQueue, read: suspend (ChunkRequest) -> List<Byte>): PullResult {
    val gallery = mutableListOf<GalleryWrite>()
    var transition = reduce(queue.copy(inFlight = null), QueueEvent.Resume)
    gallery += transition.gallery
    while (true) {
        val request = transition.read ?: return PullResult(transition.queue, gallery, finished = !hasPending(transition.queue))
        val bytes = try {
            read(request)
        } catch (_: Exception) {
            val released = reduce(transition.queue, QueueEvent.Delivered(request.identity, emptyList()))
            return PullResult(released.queue, gallery, finished = false)
        }
        if (bytes.isEmpty()) {
            val released = reduce(transition.queue, QueueEvent.Delivered(request.identity, emptyList()))
            return PullResult(released.queue, gallery, finished = false)
        }
        transition = reduce(transition.queue, QueueEvent.Delivered(request.identity, bytes))
        gallery += transition.gallery
    }
}

private fun hasPending(queue: TransferQueue): Boolean {
    return queue.items.any { item ->
        val key = identityKey(item.identity)
        key !in queue.committed && key !in queue.skipped && progress(queue, item) < item.identity.bytes
    }
}

fun reduce(queue: TransferQueue, event: QueueEvent): QueueTransition {
    return when (event) {
        is QueueEvent.Add -> pump(include(queue, event.request))
        is QueueEvent.BeginSync -> {
            val added = event.requests.fold(queue.copy(selecting = false)) { current, request ->
                include(current, request.copy(kind = TransferKind.Sync))
            }
            pump(added)
        }
        is QueueEvent.Saved -> pump(queue.copy(committed = queue.committed + identityKey(event.identity), inFlight = clearFlight(queue, event.identity)))
        is QueueEvent.Skip -> {
            val key = identityKey(event.identity)
            pump(queue.copy(skipped = queue.skipped + key, inFlight = if (queue.inFlight == key) null else queue.inFlight))
        }
        QueueEvent.Pause -> QueueTransition(queue.copy(paused = true))
        QueueEvent.Resume -> pump(queue.copy(paused = false))
        is QueueEvent.Delivered -> onDelivered(queue, event.identity, event.bytes)
        QueueEvent.Cancel -> onCancel(queue)
    }
}

private fun onDelivered(queue: TransferQueue, identity: TransferIdentity, bytes: List<Byte>): QueueTransition {
    val key = identityKey(identity)
    if (queue.inFlight != key) return QueueTransition(queue)
    if (bytes.isEmpty()) return QueueTransition(queue.copy(inFlight = null))
    val item = queue.items.firstOrNull { identityKey(it.identity) == key } ?: return QueueTransition(queue)
    if (item.kind == TransferKind.Sync) return onSyncDelivered(queue, item, bytes)
    val current = queue.temps[key].orEmpty()
    val room = (identity.bytes - current.size).coerceAtLeast(0)
    val merged = current + bytes.take(room.toInt())
    val temps = queue.temps + (key to merged)
    val done = merged.size.toLong() == identity.bytes
    val written = if (done) {
        queue.copy(temps = temps - key, committed = queue.committed + key, inFlight = null)
    } else {
        queue.copy(temps = temps, inFlight = null)
    }
    val gallery = if (done) listOf(GalleryWrite(identity, merged)) else emptyList()
    val pumped = pump(written)
    return pumped.copy(gallery = gallery + pumped.gallery)
}

private fun onSyncDelivered(queue: TransferQueue, item: TransferRequest, bytes: List<Byte>): QueueTransition {
    val identity = item.identity
    val key = identityKey(identity)
    val current = queue.received[key] ?: 0L
    val room = (identity.bytes - current).coerceAtLeast(0)
    val accepted = bytes.size.toLong().coerceAtMost(room)
    val next = current + accepted
    val done = next == identity.bytes
    val written = if (done) {
        queue.copy(received = queue.received - key, committed = queue.committed + key, inFlight = null)
    } else {
        queue.copy(received = queue.received + (key to next), inFlight = null)
    }
    val gallery = if (done) listOf(GalleryWrite(identity, emptyList())) else emptyList()
    val pumped = pump(written)
    return pumped.copy(gallery = gallery + pumped.gallery)
}

private fun onCancel(queue: TransferQueue): QueueTransition {
    val discarded = queue.items.filter { identityKey(it.identity) !in queue.committed }
    val keys = discarded.map { identityKey(it.identity) }.toSet()
    return QueueTransition(
        queue.copy(
            items = queue.items.filter { identityKey(it.identity) !in keys },
            temps = queue.temps - keys,
            received = queue.received - keys,
            skipped = queue.skipped - keys,
            inFlight = null,
            selecting = false,
        ),
        discarded = discarded.map { it.identity },
    )
}

private fun include(queue: TransferQueue, request: TransferRequest): TransferQueue {
    val key = identityKey(request.identity)
    val existing = queue.items.indexOfFirst { identityKey(it.identity) == key }
    if (existing < 0) return queue.copy(items = queue.items + request)
    val current = queue.items[existing]
    val revived = key in queue.skipped && request.kind != TransferKind.PrefetchThumb && request.kind != TransferKind.VisibleThumb
    val kind = if (rank(request.kind) < rank(current.kind)) request.kind else current.kind
    val updated = current.copy(kind = kind, handle = request.handle)
    val items = queue.items.toMutableList()
    items[existing] = updated
    return queue.copy(items = items, skipped = if (revived) queue.skipped - key else queue.skipped)
}

private fun pump(queue: TransferQueue): QueueTransition {
    if (queue.paused || queue.inFlight != null) return QueueTransition(queue)
    val next = queue.items
        .withIndex()
        .filter { (_, item) ->
            val key = identityKey(item.identity)
            key !in queue.committed && key !in queue.skipped && progress(queue, item) < item.identity.bytes
        }
        .minWithOrNull(compareBy<IndexedValue<TransferRequest>> { rank(it.value.kind) }.thenBy { it.index })
        ?.value
        ?: return QueueTransition(queue)
    val offset = progress(queue, next)
    val count = minOf(queue.chunkBytes, next.identity.bytes - offset)
    if (count <= 0) return QueueTransition(queue)
    return QueueTransition(
        queue.copy(inFlight = identityKey(next.identity)),
        read = ChunkRequest(next.identity, offset, count),
    )
}

private fun clearFlight(queue: TransferQueue, identity: TransferIdentity): String? {
    return if (queue.inFlight == identityKey(identity)) null else queue.inFlight
}

private fun progress(queue: TransferQueue, item: TransferRequest): Long {
    val key = identityKey(item.identity)
    return if (item.kind == TransferKind.Sync) queue.received[key] ?: 0L
    else queue.temps[key].orEmpty().size.toLong()
}

private fun rank(kind: TransferKind): Int {
    return when (kind) {
        TransferKind.Preview -> 0
        TransferKind.Sync -> 1
        TransferKind.VisibleThumb -> 2
        TransferKind.PrefetchThumb -> 3
    }
}
