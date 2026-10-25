package app.zsync.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class InitiatorGuidTest {
    @Test
    fun generatesSixteenBytesOncePerCall() {
        val guid = newInitiatorGuid { bytes -> bytes.fill(0xAB.toByte()) }
        assertEquals(16, guid.size)
        assertEquals("abababababababababababababababab", guidToHex(guid))
        assertContentEquals(guid, hexToGuid(guidToHex(guid)))
    }
}
