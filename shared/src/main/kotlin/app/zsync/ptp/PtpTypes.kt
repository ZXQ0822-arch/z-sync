package app.zsync.ptp

internal const val PROTOCOL_VERSION = 0x00010000

object PacketType {
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val CANCEL = 11
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14
}

object Opcode {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val GET_PARTIAL_OBJECT = 0x101B
    const val OBJECT_ADDED = 0x4002
    const val NIKON_CHANGE_APPLICATION_MODE = 0x9435
    const val NIKON_GET_PICT_CTRL = 0x90CC
    const val NIKON_SET_PICT_CTRL = 0x90CD
}

fun opcodeName(code: Int): String {
    return when (code) {
        Opcode.GET_DEVICE_INFO -> "GetDeviceInfo"
        Opcode.OPEN_SESSION -> "OpenSession"
        Opcode.CLOSE_SESSION -> "CloseSession"
        Opcode.GET_STORAGE_IDS -> "GetStorageIds"
        Opcode.GET_OBJECT_HANDLES -> "GetObjectHandles"
        Opcode.GET_OBJECT_INFO -> "GetObjectInfo"
        Opcode.GET_OBJECT -> "GetObject"
        Opcode.GET_THUMB -> "GetThumb"
        Opcode.DELETE_OBJECT -> "DeleteObject"
        Opcode.GET_PARTIAL_OBJECT -> "GetPartialObject"
        Opcode.NIKON_CHANGE_APPLICATION_MODE -> "NikonChangeApplicationMode"
        else -> "0x${code.toString(16)}"
    }
}

enum class DataPhase(val wire: Int) {
    RECEIVE_OR_NONE(1),
    SEND(2),
}

const val PARTIAL_OBJECT_WINDOW = 4 * 1024 * 1024
