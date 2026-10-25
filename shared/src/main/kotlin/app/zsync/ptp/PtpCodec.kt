package app.zsync.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

sealed interface Packet {
    fun encode(): ByteArray

    data class InitCommandRequest(val guid: ByteArray, val hostName: String) : Packet {
        override fun encode(): ByteArray {
            require(guid.size == 16) { "GUID is 16 bytes" }
            val name = hostName.encodeUtf16LeNullTerminated()
            return packet(PacketType.INIT_COMMAND_REQUEST, 16 + name.size + 4) {
                put(guid)
                put(name)
                putInt(PROTOCOL_VERSION)
            }
        }
    }

    data class InitCommandAck(
        val connectionNumber: Long,
        val guid: ByteArray,
        val cameraName: String,
    ) : Packet {
        override fun encode(): ByteArray {
            require(guid.size == 16) { "GUID is 16 bytes" }
            require(connectionNumber in 0..0xFFFF_FFFFL)
            val name = cameraName.encodeUtf16LeNullTerminated()
            return packet(PacketType.INIT_COMMAND_ACK, 4 + 16 + name.size + 4) {
                putU32(connectionNumber)
                put(guid)
                put(name)
                putInt(PROTOCOL_VERSION)
            }
        }
    }

    data class InitEventRequest(val connectionNumber: Long) : Packet {
        override fun encode(): ByteArray {
            require(connectionNumber in 0..0xFFFF_FFFFL)
            return packet(PacketType.INIT_EVENT_REQUEST, 4) { putU32(connectionNumber) }
        }
    }

    data object InitEventAck : Packet {
        override fun encode(): ByteArray = packet(PacketType.INIT_EVENT_ACK, 0) {}
    }

    data class InitFail(val reason: Long) : Packet {
        override fun encode(): ByteArray {
            require(reason in 0..0xFFFF_FFFFL)
            return packet(PacketType.INIT_FAIL, 4) { putU32(reason) }
        }
    }

    data class OperationRequest(
        val code: Int,
        val transactionId: Long,
        val dataPhase: DataPhase,
        val params: List<Long> = emptyList(),
    ) : Packet {
        override fun encode(): ByteArray {
            require(code in 0..0xFFFF)
            require(transactionId in 0..0xFFFF_FFFFL)
            require(params.size <= 5) { "PTP allows at most 5 parameters" }
            params.forEach { require(it in 0..0xFFFF_FFFFL) }
            return packet(PacketType.OPERATION_REQUEST, 4 + 2 + 4 + params.size * 4) {
                putInt(dataPhase.wire)
                putShort(code.toShort())
                putU32(transactionId)
                params.forEach { putU32(it) }
            }
        }
    }

    data class OperationResponse(
        val code: Int,
        val transactionId: Long,
        val params: List<Long> = emptyList(),
    ) : Packet {
        override fun encode(): ByteArray {
            require(code in 0..0xFFFF)
            require(transactionId in 0..0xFFFF_FFFFL)
            require(params.size <= 5)
            params.forEach { require(it in 0..0xFFFF_FFFFL) }
            return packet(PacketType.OPERATION_RESPONSE, 2 + 4 + params.size * 4) {
                putShort(code.toShort())
                putU32(transactionId)
                params.forEach { putU32(it) }
            }
        }
    }
}

fun openSessionRequest(transactionId: Long, sessionId: Long = 1): Packet.OperationRequest {
    return Packet.OperationRequest(
        code = Opcode.OPEN_SESSION,
        transactionId = transactionId,
        dataPhase = DataPhase.RECEIVE_OR_NONE,
        params = listOf(sessionId),
    )
}

fun getPartialObjectRequest(
    transactionId: Long,
    handle: Long,
    offset: Long,
    maxBytes: Long = PARTIAL_OBJECT_WINDOW.toLong(),
): Packet.OperationRequest {
    return Packet.OperationRequest(
        code = Opcode.GET_PARTIAL_OBJECT,
        transactionId = transactionId,
        dataPhase = DataPhase.RECEIVE_OR_NONE,
        params = listOf(handle, offset, maxBytes),
    )
}

fun deleteObjectRequest(transactionId: Long, handle: Long): Packet.OperationRequest {
    return Packet.OperationRequest(
        code = Opcode.DELETE_OBJECT,
        transactionId = transactionId,
        dataPhase = DataPhase.RECEIVE_OR_NONE,
        params = listOf(handle),
    )
}

fun decodePacket(bytes: ByteArray): Packet {
    require(bytes.size >= 8) { "packet is shorter than a header" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val length = buffer.int
    require(length == bytes.size) { "length field does not match the buffer" }
    return when (val type = buffer.int) {
        PacketType.INIT_COMMAND_REQUEST -> {
            val guid = ByteArray(16).also { buffer.get(it) }
            val hostName = buffer.readUtf16LeNullTerminated()
            require(buffer.int == PROTOCOL_VERSION)
            Packet.InitCommandRequest(guid, hostName)
        }
        PacketType.INIT_COMMAND_ACK -> {
            val connectionNumber = buffer.u32()
            val guid = ByteArray(16).also { buffer.get(it) }
            val cameraName = buffer.readUtf16LeNullTerminated()
            if (buffer.remaining() >= 4) buffer.int
            Packet.InitCommandAck(connectionNumber, guid, cameraName)
        }
        PacketType.INIT_EVENT_REQUEST -> Packet.InitEventRequest(buffer.u32())
        PacketType.INIT_EVENT_ACK -> Packet.InitEventAck
        PacketType.INIT_FAIL -> Packet.InitFail(buffer.u32())
        PacketType.OPERATION_REQUEST -> {
            val dataPhase = when (val wire = buffer.int) {
                DataPhase.RECEIVE_OR_NONE.wire -> DataPhase.RECEIVE_OR_NONE
                DataPhase.SEND.wire -> DataPhase.SEND
                else -> throw IllegalArgumentException("unknown data phase $wire")
            }
            val code = buffer.short.toInt() and 0xFFFF
            val transactionId = buffer.u32()
            Packet.OperationRequest(code, transactionId, dataPhase, buffer.readParams())
        }
        PacketType.OPERATION_RESPONSE -> {
            val code = buffer.short.toInt() and 0xFFFF
            val transactionId = buffer.u32()
            Packet.OperationResponse(code, transactionId, buffer.readParams())
        }
        else -> throw IllegalArgumentException("unsupported packet type $type")
    }
}

private fun packet(type: Int, payloadSize: Int, payload: ByteBuffer.() -> Unit): ByteArray {
    val length = 8 + payloadSize
    val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(length)
    buffer.putInt(type)
    buffer.payload()
    require(buffer.remaining() == 0) { "payload size does not match" }
    return buffer.array()
}

private fun ByteBuffer.putU32(value: Long) {
    putInt(value.toInt())
}

private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFF_FFFFL

private fun ByteBuffer.readParams(): List<Long> {
    require(remaining() % 4 == 0) { "parameters are not aligned" }
    require(remaining() / 4 <= 5) { "PTP allows at most 5 parameters" }
    return List(remaining() / 4) { u32() }
}

private fun String.encodeUtf16LeNullTerminated(): ByteArray {
    val encoded = toByteArray(StandardCharsets.UTF_16LE)
    return encoded + byteArrayOf(0, 0)
}

private fun ByteBuffer.readUtf16LeNullTerminated(): String {
    val start = position()
    while (remaining() >= 2) {
        if (short.toInt() == 0) {
            val byteLength = position() - start - 2
            val slice = ByteArray(byteLength)
            val saved = position()
            position(start)
            get(slice)
            position(saved)
            return String(slice, StandardCharsets.UTF_16LE)
        }
    }
    throw IllegalArgumentException("UTF-16 string is not terminated")
}
