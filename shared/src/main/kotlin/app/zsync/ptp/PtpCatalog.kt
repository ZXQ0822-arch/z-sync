package app.zsync.ptp

import app.zsync.gallery.CameraFile
import app.zsync.gallery.TRANSFER_CHUNK_BYTES
import app.zsync.gallery.userMediaFormat
import app.zsync.session.SessionTrace
import app.zsync.session.TraceEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime

const val RESPONSE_OK = 0x2001
private const val FORMAT_ASSOCIATION = 0x3001

interface PtpWire {
    suspend fun start(initPacket: ByteArray): ByteArray

    suspend fun call(request: ByteArray): PtpReply

    suspend fun callReporting(request: ByteArray, onPayload: (Long) -> Unit): PtpReply = call(request)

    fun close()
}

data class PtpReply(val data: ByteArray, val code: Int)

class PtpSession(
    private val wire: PtpWire,
    private val guid: ByteArray,
    private val trace: SessionTrace? = null,
) {
    private var transaction = 0L

    suspend fun open(): String {
        val ackBytes = wire.start(Packet.InitCommandRequest(guid, "Z-Sync").encode())
        val ack = decodePacket(ackBytes) as? Packet.InitCommandAck
            ?: error("camera did not accept the session")
        val opened = wire.call(openSessionRequest(nextTransaction()).encode())
        if (opened.code != RESPONSE_OK) error("open session ${opened.code.toString(16)}")
        return ack.cameraName
    }

    suspend fun handles(): List<Long> = catalog().map { it.handle }

    suspend fun catalog(onPhoto: suspend (CameraFile) -> Unit = {}): List<CameraFile> {
        val storages = parseIdArray(callOk(Opcode.GET_STORAGE_IDS).data)
        val ids = storages.ifEmpty { listOf(0xFFFF_FFFFL) }
        val photos = mutableListOf<CameraFile>()
        val seen = mutableSetOf<Long>()
        for (storage in ids) {
            val roots = parseIdArray(callOk(Opcode.GET_OBJECT_HANDLES, listOf(storage, 0, 0xFFFF_FFFF)).data)
            collectPhotos(storage, roots, seen, photos, onPhoto)
        }
        return photos
    }

    private suspend fun collectPhotos(
        storage: Long,
        handles: List<Long>,
        seen: MutableSet<Long>,
        photos: MutableList<CameraFile>,
        onPhoto: suspend (CameraFile) -> Unit,
    ) {
        for (handle in handles) {
            if (!seen.add(handle)) continue
            val info = callOk(Opcode.GET_OBJECT_INFO, listOf(handle)).data
            if (isFolder(info)) {
                val children = parseIdArray(callOk(Opcode.GET_OBJECT_HANDLES, listOf(storage, 0, handle)).data)
                collectPhotos(storage, children, seen, photos, onPhoto)
            } else {
                parseObjectInfo(handle, info)?.let {
                    photos.add(it)
                    onPhoto(it)
                }
            }
        }
    }

    suspend fun describe(handle: Long): CameraFile? {
        return parseObjectInfo(handle, callOk(Opcode.GET_OBJECT_INFO, listOf(handle)).data)
    }

    suspend fun thumb(handle: Long): ByteArray {
        return callOk(Opcode.GET_THUMB, listOf(handle)).data
    }

    suspend fun image(handle: Long, onBytes: ((Long) -> Unit)? = null): ByteArray {
        return image(handle, start = 0, count = null, onBytes = onBytes)
    }

    suspend fun image(
        handle: Long,
        start: Long,
        count: Long?,
        onBytes: ((Long) -> Unit)? = null,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        var offset = start
        val end = if (count == null) Long.MAX_VALUE else start + count
        while (offset < end) {
            val ask = minOf(TRANSFER_CHUNK_BYTES, end - offset)
            val reply = callOk(
                Opcode.GET_PARTIAL_OBJECT,
                listOf(handle, offset, ask),
                onBytes?.let { report ->
                    val at = offset
                    { seen: Long -> report(at + seen) }
                },
            )
            if (reply.data.isEmpty()) break
            parts += reply.data
            offset += reply.data.size
            if (reply.data.size.toLong() < ask) break
        }
        val all = ByteArray((offset - start).toInt())
        var at = 0
        parts.forEach { part ->
            part.copyInto(all, at)
            at += part.size
        }
        return all
    }

    suspend fun keepalive() {
        callOk(Opcode.GET_DEVICE_INFO)
    }

    suspend fun delete(handle: Long) {
        note("delete begin handle=$handle")
        callOk(Opcode.DELETE_OBJECT, listOf(handle))
        note("delete done handle=$handle")
    }

    private fun note(text: String) {
        val active = trace ?: return
        active.record(TraceEvent.Note(active.id, text))
    }

    fun close() = wire.close()

    private suspend fun callOk(
        code: Int,
        params: List<Long> = emptyList(),
        onPayload: ((Long) -> Unit)? = null,
    ): PtpReply {
        val transactionId = nextTransaction()
        trace?.record(
            TraceEvent.Ptp(
                traceId = trace.id,
                operation = opcodeName(code),
                transactionId = transactionId,
                handle = params.firstOrNull() ?: 0L,
                offset = if (code == Opcode.GET_PARTIAL_OBJECT) params.getOrNull(1) ?: 0L else 0L,
                chunkBytes = if (code == Opcode.GET_PARTIAL_OBJECT) params.getOrNull(2) ?: 0L else 0L,
            ),
        )
        val packet = Packet.OperationRequest(code, transactionId, DataPhase.RECEIVE_OR_NONE, params).encode()
        val reply = if (onPayload == null) wire.call(packet) else wire.callReporting(packet, onPayload)
        if (reply.code != RESPONSE_OK) error("ptp ${code.toString(16)} -> ${reply.code.toString(16)}")
        return reply
    }

    private fun nextTransaction(): Long = ++transaction
}

fun isFolder(data: ByteArray): Boolean {
    if (data.size < 6) return false
    val format = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt() and 0xFFFF
    return format == FORMAT_ASSOCIATION
}

fun parseIdArray(data: ByteArray): List<Long> {
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.remaining() < 4) return emptyList()
    val count = buffer.int
    if (count < 0 || buffer.remaining() < count * 4) return emptyList()
    return List(count) { buffer.int.toLong() and 0xFFFF_FFFFL }
}

fun parseObjectInfo(handle: Long, data: ByteArray): CameraFile? {
    if (data.size < 53) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val format = buffer.getShort(4).toInt() and 0xFFFF
    val protection = buffer.getShort(6).toInt() and 0xFFFF
    val bytes = buffer.getInt(8).toLong() and 0xFFFF_FFFFL
    val sequence = buffer.getInt(48).toLong() and 0xFFFF_FFFFL
    if (format == FORMAT_ASSOCIATION) return null
    buffer.position(52)
    val name = readPtpString(buffer)
    if (name.isBlank()) return null
    val capturedLocalAt = parseCaptureLocalDateTime(readPtpString(buffer))
    val photo = userMediaFormat(format, name) ?: return null
    return CameraFile(
        handle = handle,
        name = name,
        bytes = bytes,
        format = photo,
        burstKey = name.substringBeforeLast('.'),
        locked = protection == 0x0001,
        capturedLocalAt = capturedLocalAt,
        sequence = sequence,
    )
}

fun parseCaptureLocalDateTime(text: String): LocalDateTime? {
    val digits = text.filter { it.isDigit() }
    if (digits.length < 14) return null
    return try {
        LocalDateTime.of(
            digits.substring(0, 4).toInt(),
            digits.substring(4, 6).toInt(),
            digits.substring(6, 8).toInt(),
            digits.substring(8, 10).toInt(),
            digits.substring(10, 12).toInt(),
            digits.substring(12, 14).toInt(),
        ).takeIf { it.year in 1980..2100 }
    } catch (ignored: RuntimeException) {
        null
    }
}

private fun readPtpString(buffer: ByteBuffer): String {
    if (buffer.remaining() < 1) return ""
    val count = buffer.get().toInt() and 0xFF
    if (count <= 1 || buffer.remaining() < count * 2) return ""
    val chars = ByteArray((count - 1) * 2)
    buffer.get(chars)
    if (buffer.remaining() >= 2) buffer.short
    return String(chars, Charsets.UTF_16LE)
}
