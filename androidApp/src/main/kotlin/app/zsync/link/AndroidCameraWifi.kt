package app.zsync.link

import app.zsync.log.QuickLog
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.os.SystemClock
import app.zsync.model.isCameraSsid
import app.zsync.model.isScanFresh
import app.zsync.model.wrongPasswordForTarget
import app.zsync.session.chooseSsid
import app.zsync.session.reusesHeldWifi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

private const val CAMERA_SSID_PATTERN = ".*Z_30.*"
private const val NEW_SCAN_GAP_MS = 30_000L

/** How old a cached scan result may be before it is treated as stale. Matches [NEW_SCAN_GAP_MS]. */
private const val SCAN_FRESH_MS = 30_000L

data class CameraAp(val ssid: String, val bssid: String)

sealed interface JoinResult {
    data object Up : JoinResult

    data object Down : JoinResult

    data object WrongPassword : JoinResult

    data object Cancelled : JoinResult

    data class Error(val detail: String) : JoinResult
}

class AndroidCameraWifi(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var waiting: CancellableContinuation<JoinResult>? = null
    private var dropping = false
    var cameraNetwork: Network? = null
        private set
    var onCameraLost: (() -> Unit)? = null
    private var hold = false
    private var heldSsid: String? = null
    private var heldBssid: String? = null

    @Suppress("DEPRECATION")
    fun ssids(): List<String> {
        val nowUs = SystemClock.elapsedRealtime() * 1_000
        return try {
            wifi.scanResults
                .filter { !it.BSSID.isNullOrBlank() && isScanFresh(it.timestamp, nowUs, SCAN_FRESH_MS) }
                .map { it.SSID.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
        } catch (denied: SecurityException) {
            QuickLog.i(LOG_TAG, "scan results unavailable")
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    fun startScan() {
        try {
            wifi.startScan()
        } catch (denied: SecurityException) {
            QuickLog.i(LOG_TAG, "scan not allowed")
        }
    }

    fun defaultNetworkHasInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun refreshScan(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val due = lastStartAttemptAt == 0L || now - lastStartAttemptAt >= NEW_SCAN_GAP_MS
        val updated = if (due) {
            lastStartAttemptAt = now
            freshScan()
        } else {
            false
        }
        val seen = ssids()
        if (seen.isNotEmpty() || updated) {
            lastUpdatedNames = seen
            if (updated || lastUpdatedAt == 0L) lastUpdatedAt = now
        } else if (lastUpdatedAt != 0L && now - lastUpdatedAt > 8_000) {
            lastUpdatedNames = emptyList()
        }
        QuickLog.i(
            LOG_TAG,
            "scan cache=${lastUpdatedNames.size} camera=${lastUpdatedNames.any { isCameraSsid(it) }} updated=$updated fresh=$due",
        )
        return updated || lastUpdatedNames.isNotEmpty()
    }

    private var lastUpdatedNames: List<String> = emptyList()
    private var lastUpdatedAt = 0L
    private var lastStartAttemptAt = 0L

    fun namesFromLastUpdatedScan(): List<String> = lastUpdatedNames

    suspend fun findCamera(savedSsid: String?): CameraAp? {
        val updated = refreshScan()
        QuickLog.i(LOG_TAG, "scan finished updated=$updated location=${locationEnabled()} fresh=${lastUpdatedNames.size}")
        val names = lastUpdatedNames
        if (names.isEmpty()) return null
        return pickCamera(savedSsid, maxAgeMs = SCAN_FRESH_MS)?.takeIf { ap ->
            names.any { it.equals(ap.ssid, ignoreCase = true) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun freshScan(): Boolean {
        val finished = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                val once = AtomicBoolean(false)
                fun finish(updated: Boolean) {
                    if (!once.compareAndSet(false, true)) return
                    cont.resume(updated) { _ -> }
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        unregisterQuietly(this)
                        finish(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                    }
                }
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { unregisterQuietly(receiver) }
                val started = try {
                    wifi.startScan()
                } catch (denied: SecurityException) {
                    QuickLog.e(LOG_TAG, "scan not allowed")
                    false
                }
                QuickLog.i(LOG_TAG, "scan started=$started")
                if (!started) {
                    unregisterQuietly(receiver)
                    finish(false)
                }
            }
        }
        if (finished == null) QuickLog.e(LOG_TAG, "scan timed out")
        return finished == true
    }

    private fun unregisterQuietly(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (ignored: IllegalArgumentException) {
            // The receiver was already unregistered.
        }
    }

    private fun locationEnabled(): Boolean {
        return context.getSystemService(LocationManager::class.java).isLocationEnabled
    }

    @Suppress("DEPRECATION")
    private fun pickCamera(savedSsid: String?, maxAgeMs: Long?): CameraAp? {
        val nowUs = SystemClock.elapsedRealtime() * 1_000
        val results = try {
            wifi.scanResults
        } catch (denied: SecurityException) {
            QuickLog.i(LOG_TAG, "scan results unavailable")
            return null
        }.filter { scan ->
            !scan.BSSID.isNullOrBlank() && isScanFresh(scan.timestamp, nowUs, maxAgeMs)
        }
        val ssid = chooseSsid(results.map { it.SSID }, savedSsid)
        val best = results
            .filter { it.SSID.trim().removeSurrounding("\"") == ssid }
            .maxByOrNull { it.level }
        QuickLog.i(LOG_TAG, "camera in scan maxAge=${maxAgeMs ?: "none"}ms results=${results.size} found=${best != null}")
        return best?.let { CameraAp(ssid!!, it.BSSID) }
    }

    fun currentSsid(): String? {
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        val name = info.ssid?.trim()?.removeSurrounding("\"") ?: return null
        if (name.isBlank() || name.equals("<unknown ssid>", ignoreCase = true)) return null
        return name
    }

    @Suppress("DEPRECATION")
    suspend fun join(ap: CameraAp?, ssid: String?, password: String): JoinResult {
        val authFailed = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) return
                val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                if (error == WifiManager.ERROR_AUTHENTICATING) {
                    authFailed.set(true)
                    QuickLog.i(LOG_TAG, "supplicant authentication failed")
                }
            }
        }
        val filter = IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            return requestJoin(ap, ssid, password, authFailed)
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
                // Already unregistered.
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun requestJoin(
        ap: CameraAp?,
        ssid: String?,
        password: String,
        authFailed: AtomicBoolean,
    ): JoinResult = suspendCancellableCoroutine { cont ->
        val builder = WifiNetworkSpecifier.Builder()
        val target = when {
            ap != null -> {
                builder.setSsid(ap.ssid).setBssid(MacAddress.fromString(ap.bssid))
                "${ap.ssid} bssid=${ap.bssid}"
            }
            ssid != null -> {
                builder.setSsid(ssid)
                ssid
            }
            else -> {
                builder.setSsidPattern(PatternMatcher(CAMERA_SSID_PATTERN, PatternMatcher.PATTERN_SIMPLE_GLOB))
                "pattern $CAMERA_SSID_PATTERN"
            }
        }
        val specifier = builder.setWpa2Passphrase(password).build()
        val requestSsid = ap?.ssid ?: ssid
        val requestBssid = ap?.bssid
        if (reusesHeldWifi(hold, cameraNetwork != null, heldSsid, heldBssid, requestSsid, requestBssid)) {
            QuickLog.i(LOG_TAG, "request skipped: reusing held network ssid=$requestSsid")
            return@suspendCancellableCoroutine cont.resume(JoinResult.Up)
        }
        release()
        QuickLog.i(
            LOG_TAG,
            "request create ssid=$target passwordLength=${password.length} permissions[${permissionSummary()}]",
        )
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        QuickLog.i(LOG_TAG, "request capabilities internet=false")
        var joined = false
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                joined = true
                cameraNetwork = network
                heldSsid = requestSsid
                heldBssid = requestBssid
                QuickLog.i(LOG_TAG, "request accepted: camera network available network=$network")
                finish(JoinResult.Up)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                QuickLog.i(
                    LOG_TAG,
                    "camera network caps internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                        "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                        "captive=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}",
                )
            }

            override fun onUnavailable() {
                if (joined || dropping || callback !== this) return
                QuickLog.e(LOG_TAG, "request rejected: onUnavailable (user cancelled, timed out, or no matching network)")
                unregister(this)
                val info = try {
                    wifi.connectionInfo
                } catch (denied: SecurityException) {
                    null
                }
                val wrong = wrongPasswordForTarget(
                    authFailed = authFailed.get(),
                    seenSsid = info?.ssid,
                    seenBssid = info?.bssid,
                    targetSsid = ap?.ssid ?: ssid,
                    targetBssid = ap?.bssid,
                )
                QuickLog.i(
                    LOG_TAG,
                    "authFailed=${authFailed.get()} seen=${info?.ssid ?: "unknown"} target=${ap?.ssid ?: ssid ?: "pattern"} wrongPassword=$wrong",
                )
                finish(if (wrong) JoinResult.WrongPassword else JoinResult.Down)
            }

            override fun onLost(network: Network) {
                if (callback !== this || !hold) return
                QuickLog.e(LOG_TAG, "camera network lost network=$network")
                if (cameraNetwork == network) cameraNetwork = null
                onCameraLost?.invoke()
            }
        }
        callback = next
        waiting = cont
        cont.invokeOnCancellation {
            hold = false
            unregister(next)
        }
        try {
            // The camera has no internet. The request already does not ask for one.
            // Do not call bindProcessToNetwork.
            hold = true
            connectivity.requestNetwork(request, next)
            QuickLog.i(LOG_TAG, "request submitted, waiting for the system dialog")
        } catch (failed: RuntimeException) {
            val detail = "${failed.javaClass.simpleName}: ${failed.message}"
            QuickLog.e(LOG_TAG, "requestNetwork failed $detail", failed)
            unregister(next)
            finish(JoinResult.Error(detail))
        }
    }

    private fun permissionSummary(): String {
        return listOf(
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ).joinToString(" ") { name ->
            val granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
            "${name.substringAfterLast('.')}=$granted"
        }
    }

    fun release() {
        dropping = true
        hold = false
        cameraNetwork = null
        heldSsid = null
        heldBssid = null
        val current = callback
        callback = null
        current?.let { unregister(it) }
        finish(JoinResult.Cancelled)
        dropping = false
    }

    private fun finish(result: JoinResult) {
        val cont = waiting ?: return
        waiting = null
        if (cont.isActive) cont.resume(result)
    }

    private fun unregister(target: ConnectivityManager.NetworkCallback) {
        if (callback !== target && callback != null) return
        try {
            connectivity.unregisterNetworkCallback(target)
        } catch (ignored: IllegalArgumentException) {
            // The callback was already unregistered.
        }
        if (callback === target) callback = null
    }
}
