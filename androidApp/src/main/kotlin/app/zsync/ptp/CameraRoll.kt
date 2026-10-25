package app.zsync.ptp

import app.zsync.log.QuickLog
import android.net.ConnectivityManager
import androidx.compose.ui.graphics.ImageBitmap
import app.zsync.gallery.CameraFile
import app.zsync.gallery.PhotoOrder
import app.zsync.gallery.sortedPhotos
import app.zsync.link.AndroidCameraWifi
import app.zsync.link.LOG_TAG
import app.zsync.link.LinkStore
import app.zsync.session.SessionTrace
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ReopenResult {
    Opened,
    StillClosed,
    NoNetwork,
}

data class CameraThumb(val file: CameraFile, val image: ImageBitmap?)

data class ThumbPage(val thumbs: List<CameraThumb>, val yielded: Boolean)

data class FullPhoto(val image: ImageBitmap?, val shot: ShotInfo)

class CameraRoll(
    private val wifi: AndroidCameraWifi,
    private val connectivity: ConnectivityManager,
    private val store: LinkStore,
    val trace: SessionTrace = SessionTrace(),
) {
    private val gate = Mutex()
    private var session: PtpSession? = null
    private var catalog: List<CameraFile> = emptyList()
    private val delivered = mutableSetOf<Long>()

    val total: Int
        get() = catalog.size

    val hasSession: Boolean
        get() = session != null

    suspend fun warmSession(): Boolean = gate.withLock {
        if (session != null) return@withLock true
        val network = wifi.cameraNetwork ?: return@withLock false
        return@withLock try {
            val wire = NikonWire(network, cameraHost(connectivity, network))
            val opened = PtpSession(wire, store.guid(), trace)
            opened.open()
            session = opened
            QuickLog.i(LOG_TAG, "ptp session warmed on the camera network")
            true
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "ptp warm failed ${failed.javaClass.simpleName}: ${failed.message}")
            false
        }
    }

    /**
     * Reads the catalog and its thumbnails.
     *
     * [pace] runs before each thumbnail. The read holds the roll lock from start to finish, so the
     * caller must not block there: pausing for the background inside this loop would also stop the
     * keep-alive and let the camera drop the hotspot. A short delay only lowers the duty cycle.
     */
    suspend fun open(
        onStep: suspend (String) -> Unit = {},
        onPreview: suspend (List<CameraThumb>) -> Unit = {},
        pace: suspend () -> Unit = {},
    ): Int {
        onStep("正在连接相机…")
        return gate.withLock {
            // Reuse the session that warmSession() already opened. Closing it here makes the camera
            // treat the client as gone and switch its Wi-Fi hotspot off, which drops the phone.
            val live = session ?: run {
                val network = wifi.cameraNetwork ?: error("camera network is not up")
                val wire = NikonWire(network, cameraHost(connectivity, network))
                val opened = PtpSession(wire, store.guid(), trace)
                opened.open()
                session = opened
                opened
            }
            catalog = emptyList()
            delivered.clear()
            onStep("正在读取照片列表…")
            var found = 0
            catalog = live.catalog { photo ->
                found += 1
                onStep("正在读取照片列表… 已找到 $found 张")
                delivered += photo.handle
                pace()
                onPreview(previewThumbs(listOf(photo)))
            }
            catalog.size
        }
    }

    private suspend fun previewThumbs(files: List<CameraFile>): List<CameraThumb> {
        val live = session ?: return emptyList()
        val raw = files.map { file ->
            val bytes = try {
                live.thumb(file.handle)
            } catch (failed: Exception) {
                QuickLog.w(LOG_TAG, "thumb failed handle=${file.handle} ${failed.javaClass.simpleName}: ${failed.message}")
                ByteArray(0)
            }
            file to bytes
        }
        return withContext(Dispatchers.Default) {
            raw.map { (file, bytes) -> CameraThumb(file, decodeThumb(bytes)) }
        }
    }

    /**
     * Reads one page of thumbnails. [paused] runs before each file, outside the roll lock, so the
     * caller may wait there: the grid simply stops filling while the app is not on screen.
     */
    suspend fun next(
        order: PhotoOrder,
        count: Int = 10,
        yield: () -> Boolean = { false },
        paused: suspend () -> Unit = {},
    ): ThumbPage {
        val slice = gate.withLock {
            if (session == null) return@withLock emptyList()
            sortedPhotos(catalog, order).filter { it.handle !in delivered }.take(count)
        }
        if (slice.isEmpty()) return ThumbPage(emptyList(), yielded = false)
        val raw = mutableListOf<Pair<CameraFile, ByteArray>>()
        for (file in slice) {
            if (yield()) return ThumbPage(decodeThumbs(raw), yielded = true)
            paused()
            val bytes = gate.withLock {
                val live = session ?: return@withLock null
                delivered += file.handle
                try {
                    live.thumb(file.handle)
                } catch (failed: Exception) {
                    QuickLog.w(LOG_TAG, "thumb failed handle=${file.handle} ${failed.javaClass.simpleName}: ${failed.message}")
                    ByteArray(0)
                }
            } ?: return ThumbPage(decodeThumbs(raw), yielded = false)
            raw += file to bytes
        }
        return ThumbPage(decodeThumbs(raw), yielded = false)
    }

    private suspend fun decodeThumbs(raw: List<Pair<CameraFile, ByteArray>>): List<CameraThumb> {
        return withContext(Dispatchers.Default) {
            raw.map { (file, bytes) -> CameraThumb(file, decodeThumb(bytes)) }
        }
    }

    suspend fun full(handle: Long): FullPhoto? {
        val bytes = gate.withLock {
            val live = session ?: return null
            val bytes = live.image(handle)
            QuickLog.i(LOG_TAG, "ptp object handle=$handle bytes=${bytes.size} thread=${Thread.currentThread().name}")
            bytes
        }
        return withContext(Dispatchers.Default) {
            QuickLog.i(LOG_TAG, "decode full bytes=${bytes.size} thread=${Thread.currentThread().name}")
            FullPhoto(decodeForScreen(bytes), readShot(bytes))
        }
    }

    suspend fun bytes(handle: Long, onBytes: (Long) -> Unit = {}): ByteArray? = readPartial(handle, 0, null, onBytes)

    suspend fun readPartial(
        handle: Long,
        offset: Long,
        count: Long?,
        onBytes: (Long) -> Unit = {},
    ): ByteArray? = gate.withLock {
        val live = session ?: return null
        val bytes = if (count == null && offset == 0L) {
            live.image(handle, onBytes)
        } else {
            live.image(handle, start = offset, count = count, onBytes = onBytes)
        }
        QuickLog.i(LOG_TAG, "ptp partial handle=$handle offset=$offset count=${count ?: "all"} bytes=${bytes.size}")
        bytes
    }

    suspend fun delete(handle: Long): Boolean = gate.withLock {
        val live = session ?: return@withLock false
        try {
            live.delete(handle)
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "ptp delete failed handle=$handle ${failed.javaClass.simpleName}: ${failed.message}")
            return@withLock false
        }
        catalog = catalog.filter { it.handle != handle }
        delivered.remove(handle)
        QuickLog.i(LOG_TAG, "ptp delete handle=$handle")
        true
    }

    /**
     * Deletes a whole batch under one lock. A deferred delete gathers the photos the person removed
     * and clears them here, so the camera jumps to shooting once for the batch instead of once per
     * photo. Returns the handles that were actually deleted; failures stay in the caller's queue.
     */
    suspend fun deleteAll(handles: Collection<Long>): Set<Long> = gate.withLock {
        val live = session ?: return@withLock emptySet()
        val removed = mutableSetOf<Long>()
        for (handle in handles) {
            try {
                live.delete(handle)
                removed += handle
            } catch (failed: Exception) {
                QuickLog.e(LOG_TAG, "ptp deferred delete failed handle=$handle ${failed.javaClass.simpleName}: ${failed.message}")
            }
        }
        if (removed.isNotEmpty()) {
            catalog = catalog.filter { it.handle !in removed }
            delivered.removeAll(removed)
        }
        QuickLog.i(LOG_TAG, "ptp deferred delete removed=${removed.size} of ${handles.size}")
        removed
    }

    suspend fun reopenSession(): ReopenResult = gate.withLock {
        val network = wifi.cameraNetwork ?: return@withLock ReopenResult.NoNetwork
        session?.close()
        session = null
        return@withLock try {
            val wire = NikonWire(network, cameraHost(connectivity, network))
            val opened = PtpSession(wire, store.guid(), trace)
            opened.open()
            session = opened
            QuickLog.i(LOG_TAG, "ptp session reopened on the same network")
            ReopenResult.Opened
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "ptp reopen failed ${failed.javaClass.simpleName}: ${failed.message}")
            ReopenResult.StillClosed
        }
    }

    /**
     * Sends the idle heartbeat and reports what the wire did, instead of swallowing the error.
     *
     * The caller needs the difference: a closed socket means the session is gone and the person is
     * still looking at photos that can no longer be read, while a timeout may just be a slow camera.
     */
    suspend fun keepaliveIfIdle(): KeepAliveOutcome {
        if (!gate.tryLock()) return KeepAliveOutcome.Skipped
        try {
            val live = session ?: return KeepAliveOutcome.Skipped
            live.keepalive()
            return KeepAliveOutcome.Sent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timedOut: SocketTimeoutException) {
            QuickLog.i(LOG_TAG, "ptp keepalive timed out")
            return KeepAliveOutcome.Timeout
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "ptp keepalive dead ${failed.javaClass.simpleName}: ${failed.message}")
            return KeepAliveOutcome.Dead
        } finally {
            gate.unlock()
        }
    }

    fun close() {
        if (session != null) QuickLog.i(LOG_TAG, "ptp session closed by the app")
        runBlocking(Dispatchers.IO) {
            gate.withLock {
                session?.close()
                session = null
                catalog = emptyList()
                delivered.clear()
            }
        }
    }
}
