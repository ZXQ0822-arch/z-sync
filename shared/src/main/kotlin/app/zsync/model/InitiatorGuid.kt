package app.zsync.model

import java.security.SecureRandom

fun newInitiatorGuid(fill: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) }): ByteArray {
    return ByteArray(16).also(fill)
}

fun guidToHex(bytes: ByteArray): String {
    require(bytes.size == 16) { "GUID is 16 bytes" }
    return bytes.joinToString("") { "%02x".format(it) }
}

fun hexToGuid(hex: String): ByteArray {
    require(hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "GUID hex is 32 characters"
    }
    return ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
