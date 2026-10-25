package app.zsync.ui.connect

import app.zsync.log.QuickLog
import android.app.Application
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.zsync.link.AndroidCameraWifi
import app.zsync.link.CameraLink
import app.zsync.link.EncryptedLinkStore
import app.zsync.link.LOG_TAG
import app.zsync.link.WifiCameraLink
import app.zsync.ptp.CameraRoll
import app.zsync.session.DisconnectCause
import app.zsync.session.SessionTrace
import app.zsync.session.TraceEvent

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val wifi = AndroidCameraWifi(app)
    private val store = EncryptedLinkStore(app)
    private val trace = SessionTrace { line -> QuickLog.i(LOG_TAG, line) }
    val roll = CameraRoll(
        wifi = wifi,
        connectivity = app.getSystemService(ConnectivityManager::class.java),
        store = store,
        trace = trace,
    )
    val link: CameraLink = WifiCameraLink(
        scope = viewModelScope,
        store = store,
        wifi = wifi,
        onWarmPtp = { roll.warmSession() },
        onCameraConnected = { onCameraConnected?.invoke() },
        trace = trace,
    )

    /** Called on the main thread once the camera is fully connected (PTP open). */
    var onCameraConnected: (() -> Unit)? = null

    override fun onCleared() {
        trace.record(TraceEvent.Ended(trace.id, DisconnectCause.ProcessEnded))
        super.onCleared()
    }
}
