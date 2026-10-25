package app.zsync.ptp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PtpCodecTest {
    @Test
    fun initCommandRequestMatchesTheGphotoLayout() {
        val guid = ByteArray(16) { 0x11 }
        val packet = Packet.InitCommandRequest(guid, "QL").encode()
        assertEquals(
            "2200000001000000" +
                "11111111111111111111111111111111" +
                "51004c000000" +
                "00000100",
            packet.hex(),
        )
        val decoded = assertIs<Packet.InitCommandRequest>(decodePacket(packet))
        assertContentEquals(guid, decoded.guid)
        assertEquals("QL", decoded.hostName)
    }

    @Test
    fun initCommandAckRoundTripsTheConnectionNumber() {
        val guid = ByteArray(16) { 0x22 }
        val packet = Packet.InitCommandAck(7, guid, "Z 30").encode()
        val decoded = assertIs<Packet.InitCommandAck>(decodePacket(packet))
        assertEquals(7, decoded.connectionNumber)
        assertContentEquals(guid, decoded.guid)
        assertEquals("Z 30", decoded.cameraName)
    }

    @Test
    fun initEventRequestIsTwelveBytes() {
        val packet = Packet.InitEventRequest(7).encode()
        assertEquals("0c0000000300000007000000", packet.hex())
        assertEquals(7, assertIs<Packet.InitEventRequest>(decodePacket(packet)).connectionNumber)
        assertEquals(PacketType.INIT_EVENT_ACK, headerType(Packet.InitEventAck.encode()))
        assertIs<Packet.InitEventAck>(decodePacket(Packet.InitEventAck.encode()))
    }

    @Test
    fun openSessionGetPartialAndDeleteUseLittleEndianFields() {
        val open = openSessionRequest(transactionId = 1).encode()
        assertEquals(
            "16000000060000000100000002100100000001000000",
            open.hex(),
        )

        val partial = getPartialObjectRequest(
            transactionId = 2,
            handle = 9,
            offset = 0x1000,
            maxBytes = PARTIAL_OBJECT_WINDOW.toLong(),
        )
        assertEquals(Opcode.GET_PARTIAL_OBJECT, partial.code)
        assertEquals(listOf(9L, 0x1000L, 4L * 1024 * 1024), partial.params)
        assertEquals(partial, decodePacket(partial.encode()))

        val delete = deleteObjectRequest(transactionId = 3, handle = 9)
        assertEquals(Opcode.DELETE_OBJECT, delete.code)
        assertEquals(listOf(9L), delete.params)
        assertEquals(DataPhase.RECEIVE_OR_NONE, delete.dataPhase)
        assertEquals(delete, decodePacket(delete.encode()))
    }

    @Test
    fun operationResponseRoundTrips() {
        val response = Packet.OperationResponse(0x2001, transactionId = 4, params = listOf(9))
        assertEquals(response, decodePacket(response.encode()))
    }

    @Test
    fun aShortBufferIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            decodePacket(byteArrayOf(1, 2, 3))
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private fun headerType(bytes: ByteArray): Int {
    return (bytes[4].toInt() and 0xFF) or
        ((bytes[5].toInt() and 0xFF) shl 8) or
        ((bytes[6].toInt() and 0xFF) shl 16) or
        ((bytes[7].toInt() and 0xFF) shl 24)
}
