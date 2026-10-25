package app.zsync.ptp

import app.zsync.log.QuickLog
import android.net.ConnectivityManager
import android.net.Network
import app.zsync.link.LOG_TAG
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PTP_PORT = 15740
private const val COMMAND_READ_TIMEOUT_MS = 30_000

/** 0 blocks until the camera writes an event or the socket closes; a timeout here kills the reader. */
private const val EVENT_READ_TIMEOUT_MS = 0
private const val RECONNECT_WAIT_MS = 1_000L

class NikonWire(private val network: Network, private val host: InetAddress) : PtpWire {
    private var command: Socket? = null
    private var events: Socket? = null
    private var eventThread: Thread? = null

    override suspend fun start(initPacket: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val replaced = LiveWire.claim(this@NikonWire)
        if (replaced) {
            QuickLog.i(LOG_TAG, "ptp closed the previous session before reconnect")
            delay(RECONNECT_WAIT_MS)
        }
        try {
            openSession(initPacket)
        } catch (closed: IllegalStateException) {
            if (closed.message != "camera closed the connection") throw closed
            QuickLog.i(LOG_TAG, "ptp init rejected by camera, retrying once")
            closeSockets()
            delay(RECONNECT_WAIT_MS)
            openSession(initPacket)
        }
    }

    override suspend fun call(request: ByteArray): PtpReply = exchange(request, null)

    override suspend fun callReporting(request: ByteArray, onPayload: (Long) -> Unit): PtpReply {
        return exchange(request, onPayload)
    }

    private suspend fun exchange(request: ByteArray, onPayload: ((Long) -> Unit)?): PtpReply = withContext(Dispatchers.IO) {
        val socket = command ?: error("ptp session is not open")
        val output = socket.getOutputStream()
        output.write(request)
        output.flush()
        val payload = ByteArrayOutputStream()
        val input = socket.getInputStream()
        var received = 0L
        while (true) {
            val packet = readPacket(input) { chunk ->
                received += chunk
                onPayload?.invoke(received)
            }
            val type = ByteBuffer.wrap(packet, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (type) {
                PacketType.DATA, PacketType.END_DATA -> if (packet.size > 12) {
                    payload.write(packet, 12, packet.size - 12)
                }
                PacketType.OPERATION_RESPONSE -> {
                    val response = decodePacket(packet) as Packet.OperationResponse
                    return@withContext PtpReply(payload.toByteArray(), response.code)
                }
            }
        }
        error("camera closed the connection")
    }

    override fun close() {
        closeSockets()
        LiveWire.forget(this)
    }

    private fun openSession(initPacket: ByteArray): ByteArray {
        QuickLog.i(LOG_TAG, "ptp connect host=$host port=$PTP_PORT")
        val socket = open(host, COMMAND_READ_TIMEOUT_MS)
        command = socket
        socket.getOutputStream().write(initPacket)
        socket.getOutputStream().flush()
        val ack = readPacket(socket.getInputStream())
        val decoded = decodePacket(ack) as? Packet.InitCommandAck
            ?: error("camera did not accept the session")
        val event = open(host, EVENT_READ_TIMEOUT_MS)
        events = event
        event.getOutputStream().write(Packet.InitEventRequest(decoded.connectionNumber).encode())
        event.getOutputStream().flush()
        readPacket(event.getInputStream())
        startEventRead(event)
        return ack
    }

    private fun closeSockets() {
        command?.closeQuietly()
        events?.closeQuietly()
        command = null
        events = null
        val reader = eventThread
        eventThread = null
        if (reader != null && Thread.currentThread() !== reader) reader.join(500)
    }

    private fun startEventRead(socket: Socket) {
        eventThread?.let { if (Thread.currentThread() !== it) it.join(500) }
        eventThread = Thread({
            try {
                val input = socket.getInputStream()
                while (!socket.isClosed) readPacket(input)
            } catch (ended: Exception) {
                QuickLog.i(LOG_TAG, "ptp event read ended ${ended.javaClass.simpleName}")
            }
        }, "z-sync-ptp-events").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Opens one of the two PTP sockets.
     *
     * [readTimeoutMs] must be 30 s for the command socket, so a stuck call cannot block the roll
     * forever. The event socket must not time out: the camera sends nothing for long stretches, and a
     * read timeout there killed the reader exactly 30 s after every session opened (`0.1.27-1` log),
     * after which the app never read a camera event again.
     */
    private fun open(host: InetAddress, readTimeoutMs: Int): Socket {
        val socket = network.socketFactory.createSocket() as Socket
        socket.connect(InetSocketAddress(host, PTP_PORT), 5_000)
        socket.soTimeout = readTimeoutMs
        socket.tcpNoDelay = true
        return socket
    }
}

fun cameraHost(connectivity: ConnectivityManager, network: Network): InetAddress {
    val props = connectivity.getLinkProperties(network)
    val gateway = props?.dhcpServerAddress
        ?: props?.routes?.firstOrNull { it.gateway != null && it.gateway?.isAnyLocalAddress == false }?.gateway
    val host = gateway ?: InetAddress.getByName("192.168.1.1")
    QuickLog.i(LOG_TAG, "ptp camera host=$host")
    return host
}

private fun readPacket(input: InputStream, onChunk: ((Int) -> Unit)? = null): ByteArray {
    val header = ByteArray(4)
    readFully(input, header, null)
    val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
    if (length < 8 || length > 64 * 1024 * 1024) error("bad ptp length $length")
    val rest = ByteArray(length - 4)
    readFully(input, rest, onChunk)
    return header + rest
}

private fun readFully(input: InputStream, buffer: ByteArray, onChunk: ((Int) -> Unit)?) {
    var offset = 0
    var since = 0
    while (offset < buffer.size) {
        val read = input.read(buffer, offset, buffer.size - offset)
        if (read < 0) error("camera closed the connection")
        offset += read
        since += read
        if (onChunk != null && (since >= 64 * 1024 || offset == buffer.size)) {
            onChunk(since)
            since = 0
        }
    }
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (ignored: Exception) {
        // Already closed.
    }
}

private object LiveWire {
    private val lock = Any()
    private var current: NikonWire? = null

    fun claim(wire: NikonWire): Boolean {
        val previous = synchronized(lock) {
            val old = current?.takeUnless { it === wire }
            current = wire
            old
        }
        previous?.close()
        return previous != null
    }

    fun forget(wire: NikonWire) {
        synchronized(lock) {
            if (current === wire) current = null
        }
    }
}
