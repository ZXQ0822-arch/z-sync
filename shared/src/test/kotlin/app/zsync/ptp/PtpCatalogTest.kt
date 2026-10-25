package app.zsync.ptp

import app.zsync.gallery.PhotoFormat
import app.zsync.gallery.TRANSFER_CHUNK_BYTES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtpCatalogTest {
    @Test
    fun readsHandlesAndAJpegName() {
        assertEquals(listOf(7L, 8L), parseIdArray(idArray(7, 8)))

        val info = objectInfo(
            name = "DSC_0101.JPG",
            format = 0x3801,
            bytes = 3_000_000,
            protection = 0,
            sequence = 42,
            captured = "20261021T215900",
        )
        val file = parseObjectInfo(7, info)!!
        assertEquals("DSC_0101.JPG", file.name)
        assertEquals(PhotoFormat.JPEG, file.format)
        assertEquals(3_000_000L, file.bytes)
        assertEquals(false, file.locked)
        assertEquals(LocalDateTime.of(2026, 10, 21, 21, 59), file.capturedLocalAt)
        assertEquals(LocalDate.of(2026, 10, 21), file.captureDate)
        assertEquals(42L, file.sequence)
    }

    @Test
    fun captureDateFallsBackWhenTheCameraOmitsIt() {
        val info = objectInfo(name = "DSC_0101.JPG", format = 0x3801, bytes = 10, protection = 0, sequence = 7)
        val file = parseObjectInfo(7, info)!!
        assertEquals(null, file.capturedLocalAt)
        assertEquals(null, file.captureDate)
        assertEquals(7L, file.sequence)
        assertEquals(null, parseCaptureLocalDateTime(""))
        assertEquals(null, parseCaptureLocalDateTime("not-a-date"))
    }

    @Test
    fun keepsCaptureDateAsCameraLocalWallClock() {
        val file = parseObjectInfo(
            7,
            objectInfo(
                name = "DSC_0101.JPG",
                format = 0x3801,
                bytes = 10,
                protection = 0,
                captured = "20261021T235900",
            ),
        )!!

        assertEquals(LocalDateTime.of(2026, 10, 21, 23, 59), file.capturedLocalAt)
        assertEquals(LocalDate.of(2026, 10, 21), file.captureDate)
    }

    @Test
    fun rejectsInvalidCameraLocalDates() {
        assertNull(parseCaptureLocalDateTime(""))
        assertNull(parseCaptureLocalDateTime("20230229T120000"))
        assertNull(parseCaptureLocalDateTime("19791231T235959"))
        assertEquals(
            LocalDateTime.of(2024, 2, 29, 0, 0),
            parseCaptureLocalDateTime("20240229T000000"),
        )
    }

    @Test
    fun skipsFolders() {
        val folder = objectInfo(name = "DCIM", format = 0x3001, bytes = 0, protection = 0)
        assertTrue(isFolder(folder))
        assertNull(parseObjectInfo(1, folder))
    }

    @Test
    fun listsPhotosInsideNestedFolders() = runBlocking {
        val session = PtpSession(folderTree(), ByteArray(16))
        session.open()
        assertEquals(listOf(21L, 20L), session.handles())
    }

    @Test
    fun reportsEachPhotoWhileTheListIsStillBeingRead() = runBlocking {
        val session = PtpSession(folderTree(), ByteArray(16))
        session.open()
        val names = mutableListOf<String>()
        val photos = session.catalog { names += it.name }
        assertEquals(listOf("B.JPG", "A.JPG"), names)
        assertEquals(names, photos.map { it.name })
    }

    @Test
    fun deleteIsAPureDeleteObjectWithNoModeSwitch() = runBlocking {
        val ops = mutableListOf<Pair<Int, List<Long>>>()
        val session = PtpSession(recordingWire(ops) { RESPONSE_OK }, ByteArray(16))
        session.open()
        session.delete(9)
        session.delete(8)
        assertEquals(
            listOf(
                Opcode.DELETE_OBJECT to listOf(9L),
                Opcode.DELETE_OBJECT to listOf(8L),
            ),
            ops.drop(1),
        )
    }

    @Test
    fun deleteSendsTheCommandAndLeavesThePhotoWhenTheCameraRefuses() = runBlocking {
        val deleted = mutableListOf<Long>()
        val wire = object : PtpWire {
            override suspend fun start(initPacket: ByteArray): ByteArray {
                return Packet.InitCommandAck(1, ByteArray(16), "Z 30").encode()
            }

            override suspend fun call(request: ByteArray): PtpReply {
                val operation = decodePacket(request) as Packet.OperationRequest
                return when (operation.code) {
                    Opcode.OPEN_SESSION -> PtpReply(ByteArray(0), RESPONSE_OK)
                    Opcode.DELETE_OBJECT -> {
                        val handle = operation.params.single()
                        if (handle == 9L) {
                            deleted += handle
                            PtpReply(ByteArray(0), RESPONSE_OK)
                        } else {
                            PtpReply(ByteArray(0), 0x2002)
                        }
                    }
                    else -> error("unexpected opcode ${operation.code}")
                }
            }

            override fun close() = Unit
        }
        val session = PtpSession(wire, ByteArray(16))
        session.open()
        session.delete(9)
        assertEquals(listOf(9L), deleted)
        assertFailsWith<IllegalStateException> { session.delete(4) }
        assertEquals(listOf(9L), deleted)
    }

    @Test
    fun aRangeStartsAtTheSafeOffsetAndStopsAtTheRequestedCount() = runBlocking {
        val original = ByteArray(TRANSFER_CHUNK_BYTES.toInt() + 3) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val wire = object : PtpWire {
            override suspend fun start(initPacket: ByteArray): ByteArray {
                return Packet.InitCommandAck(1, ByteArray(16), "Z 30").encode()
            }

            override suspend fun call(request: ByteArray): PtpReply {
                val operation = decodePacket(request) as Packet.OperationRequest
                if (operation.code == Opcode.OPEN_SESSION) return PtpReply(ByteArray(0), RESPONSE_OK)
                if (operation.code != Opcode.GET_PARTIAL_OBJECT) error("unexpected opcode ${operation.code}")
                val offset = operation.params[1]
                val count = operation.params[2]
                assertEquals(TRANSFER_CHUNK_BYTES, offset)
                assertEquals(3L, count)
                val start = offset.toInt()
                return PtpReply(original.copyOfRange(start, start + count.toInt()), RESPONSE_OK)
            }

            override suspend fun callReporting(request: ByteArray, onPayload: (Long) -> Unit): PtpReply {
                val reply = call(request)
                onPayload(reply.data.size.toLong())
                return reply
            }

            override fun close() = Unit
        }
        val session = PtpSession(wire, ByteArray(16))
        session.open()
        val bytes = session.image(9, start = TRANSFER_CHUNK_BYTES, count = 3) { progress += it }
        assertEquals(original.copyOfRange(TRANSFER_CHUNK_BYTES.toInt(), original.size).toList(), bytes.toList())
        assertEquals(listOf(original.size.toLong()), progress)
    }

    @Test
    fun anOriginalIsReadInOneMegabytePieces() = runBlocking {
        val shortTail = ByteArray(TRANSFER_CHUNK_BYTES.toInt() + 3) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val short = readOriginal(shortTail) { progress += it }
        assertEquals(shortTail.toList(), short.bytes.toList())
        assertEquals(listOf(0L, TRANSFER_CHUNK_BYTES), short.offsets)
        assertEquals(listOf(TRANSFER_CHUNK_BYTES, shortTail.size.toLong()), progress)
        assertTrue(short.codes.none { it == Opcode.GET_OBJECT })

        val exact = ByteArray(TRANSFER_CHUNK_BYTES.toInt()) { 7 }
        val whole = readOriginal(exact)
        assertEquals(exact.toList(), whole.bytes.toList())
        assertEquals(listOf(0L, TRANSFER_CHUNK_BYTES), whole.offsets)

        val none = readOriginal(ByteArray(0))
        assertEquals(0, none.bytes.size)
        assertEquals(listOf(0L), none.offsets)

        assertFailsWith<IllegalStateException> {
            readOriginal(shortTail, failAtOffset = TRANSFER_CHUNK_BYTES)
        }
        Unit
    }

    private fun readOriginal(
        original: ByteArray,
        failAtOffset: Long? = null,
        onBytes: ((Long) -> Unit)? = null,
    ): ReadOriginal = runBlocking {
        val codes = mutableListOf<Int>()
        val offsets = mutableListOf<Long>()
        val wire = object : PtpWire {
            override suspend fun start(initPacket: ByteArray): ByteArray {
                return Packet.InitCommandAck(1, ByteArray(16), "Z 30").encode()
            }

            override suspend fun call(request: ByteArray): PtpReply {
                val operation = decodePacket(request) as Packet.OperationRequest
                codes += operation.code
                if (operation.code == Opcode.OPEN_SESSION) return PtpReply(ByteArray(0), RESPONSE_OK)
                if (operation.code != Opcode.GET_PARTIAL_OBJECT) error("unexpected opcode ${operation.code}")
                val offset = operation.params[1]
                offsets += offset
                assertEquals(TRANSFER_CHUNK_BYTES, operation.params[2])
                if (offset == failAtOffset) return PtpReply(ByteArray(0), 0x2002)
                val start = offset.toInt()
                if (start >= original.size) return PtpReply(ByteArray(0), RESPONSE_OK)
                val end = minOf(start + TRANSFER_CHUNK_BYTES.toInt(), original.size)
                return PtpReply(original.copyOfRange(start, end), RESPONSE_OK)
            }

            override suspend fun callReporting(request: ByteArray, onPayload: (Long) -> Unit): PtpReply {
                val reply = call(request)
                onPayload(reply.data.size.toLong())
                return reply
            }

            override fun close() = Unit
        }
        val session = PtpSession(wire, ByteArray(16))
        session.open()
        ReadOriginal(session.image(9, onBytes), codes, offsets)
    }

    private class ReadOriginal(val bytes: ByteArray, val codes: List<Int>, val offsets: List<Long>)

    private fun recordingWire(
        ops: MutableList<Pair<Int, List<Long>>>,
        codeFor: (Int) -> Int,
    ): PtpWire = object : PtpWire {
        override suspend fun start(initPacket: ByteArray): ByteArray {
            return Packet.InitCommandAck(1, ByteArray(16), "Z 30").encode()
        }

        override suspend fun call(request: ByteArray): PtpReply {
            val operation = decodePacket(request) as Packet.OperationRequest
            ops += operation.code to operation.params
            return PtpReply(ByteArray(0), codeFor(operation.code))
        }

        override fun close() = Unit
    }

    private fun folderTree(): PtpWire = object : PtpWire {
        override suspend fun start(initPacket: ByteArray): ByteArray {
            return Packet.InitCommandAck(1, ByteArray(16), "Z 30").encode()
        }

        override suspend fun call(request: ByteArray): PtpReply {
            val operation = decodePacket(request) as Packet.OperationRequest
            return when (operation.code) {
                Opcode.OPEN_SESSION -> PtpReply(ByteArray(0), RESPONSE_OK)
                Opcode.GET_STORAGE_IDS -> PtpReply(idArray(1), RESPONSE_OK)
                Opcode.GET_OBJECT_HANDLES -> PtpReply(idArray(*children(operation.params[2])), RESPONSE_OK)
                Opcode.GET_OBJECT_INFO -> PtpReply(infoFor(operation.params[0]), RESPONSE_OK)
                else -> error("unexpected opcode ${operation.code}")
            }
        }

        override fun close() = Unit

        private fun children(parent: Long): IntArray = when (parent) {
            0xFFFF_FFFFL -> intArrayOf(10)
            10L -> intArrayOf(11, 20)
            11L -> intArrayOf(21)
            else -> intArrayOf()
        }

        private fun infoFor(handle: Long): ByteArray = when (handle) {
            10L -> objectInfo("DCIM", 0x3001, 0, 0)
            11L -> objectInfo("100NZ30", 0x3001, 0, 0)
            20L -> objectInfo("A.JPG", 0x3801, 100, 0)
            21L -> objectInfo("B.JPG", 0x3801, 200, 0)
            else -> error("unexpected handle $handle")
        }
    }

    private fun idArray(vararg ids: Int): ByteArray {
        val buffer = ByteBuffer.allocate(4 + ids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(ids.size)
        ids.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun objectInfo(
        name: String,
        format: Int,
        bytes: Int,
        protection: Int,
        sequence: Int = 0,
        captured: String = "",
    ): ByteArray {
        val encoded = name.toByteArray(Charsets.UTF_16LE)
        val date = captured.toByteArray(Charsets.UTF_16LE)
        val dateBytes = if (captured.isEmpty()) 0 else 1 + date.size + 2
        val buffer = ByteBuffer.allocate(52 + 1 + encoded.size + 2 + dateBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x00010001)
        buffer.putShort(format.toShort())
        buffer.putShort(protection.toShort())
        buffer.putInt(bytes)
        buffer.position(48)
        buffer.putInt(sequence)
        buffer.position(52)
        buffer.put((name.length + 1).toByte())
        buffer.put(encoded)
        buffer.putShort(0)
        if (captured.isNotEmpty()) {
            buffer.put((captured.length + 1).toByte())
            buffer.put(date)
            buffer.putShort(0)
        }
        return buffer.array()
    }
}
