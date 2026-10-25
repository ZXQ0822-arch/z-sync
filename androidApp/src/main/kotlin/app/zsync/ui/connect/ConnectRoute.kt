package app.zsync.ui.connect

import app.zsync.log.QuickLog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.zsync.BuildConfig
import app.zsync.link.CameraLink
import app.zsync.link.LOG_TAG
import app.zsync.model.isAcceptableHotspotPassword
import app.zsync.session.JourneyEvent
import app.zsync.session.JourneyModal
import app.zsync.session.JourneyPage
import app.zsync.session.LinkFailure
import app.zsync.session.LinkPhase
import app.zsync.session.journeyForUi
import app.zsync.session.joins
import app.zsync.session.primaryEvent
import app.zsync.session.savedEvent
import app.zsync.session.secondaryEvent
import app.zsync.session.usesJourneyUi

private fun retryLabel(phase: LinkPhase): String = when {
    phase is LinkPhase.WaitingForHotspot -> "我已打开"
    phase is LinkPhase.Failed && phase.failure == LinkFailure.HotspotOff -> "我已打开"
    phase is LinkPhase.Failed -> "重试"
    else -> "连接"
}

@Composable
fun ConnectRoute(link: CameraLink) {
    val context = LocalContext.current
    val phase by link.phase.collectAsState()
    val passwordSaved by link.passwordSaved.collectAsState()
    val lastCamera by link.lastCamera.collectAsState()
    val seenHotspots by link.seenHotspots.collectAsState()
    val connection by link.connection.collectAsState()
    val mediaReady = connection.connected()
    val oddFailure = (phase as? LinkPhase.Failed)?.failure
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        result.forEach { (name, granted) ->
            val line = "permission ${name.substringAfterLast('.')} granted=$granted"
            if (granted) QuickLog.i(LOG_TAG, line) else QuickLog.e(LOG_TAG, line)
        }
        val location = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (location) afterPermission?.invoke()
    }
    val allowThen: (() -> Unit) -> Unit = { action ->
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        QuickLog.i(LOG_TAG, "permissions missing=${missing.map { it.substringAfterLast('.') }}")
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermission = action
            QuickLog.i(LOG_TAG, "requesting permissions ${missing.map { it.substringAfterLast('.') }}")
            launcher.launch(missing.toTypedArray())
        }
    }
    var typed by remember { mutableStateOf("") }
    var modal by remember { mutableStateOf(JourneyModal.None) }
    LaunchedEffect(connection.journey.modal) {
        if (connection.journey.modal != JourneyModal.None) modal = connection.journey.modal
    }
    LaunchedEffect(Unit) {
        QuickLog.i(LOG_TAG, "connect screen opened phase=$phase saved=$passwordSaved")
        allowThen { link.screenOpened() }
    }
    if (usesJourneyUi(connection, oddFailure)) {
        val shown = journeyForUi(connection, modal).copy(
            seen = seenHotspots.ifEmpty { connection.journey.seen },
            typedPassword = typed.ifEmpty { connection.journey.typedPassword },
        )
        BackHandler(enabled = modal != JourneyModal.None || joins(shown.page)) {
            if (modal != JourneyModal.None) {
                modal = JourneyModal.None
                link.user(JourneyEvent.Dismiss)
            }
        }
        JourneyScreen(
            state = shown,
            version = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            onPassword = { value ->
                typed = value
                link.user(JourneyEvent.PasswordChanged(value))
            },
            onPrimary = {
                val now = SystemClock.elapsedRealtime()
                when (shown.page) {
                    JourneyPage.FirstHome, JourneyPage.SecondReturn, JourneyPage.FirstCantFind, JourneyPage.SecondCantFind,
                    JourneyPage.FirstPasswordError, JourneyPage.SecondPasswordError,
                    -> primaryEvent(shown, now)?.let { event ->
                        QuickLog.i(LOG_TAG, "tap primary page=${shown.page} phase=$phase")
                        allowThen { link.user(event) }
                    }
                    else -> Unit
                }
            },
            onSecondary = {
                secondaryEvent(shown)?.let { event ->
                    QuickLog.i(LOG_TAG, "tap secondary page=${shown.page} phase=$phase")
                    link.user(event)
                }
            },
            onSaved = {
                val now = SystemClock.elapsedRealtime()
                savedEvent(shown, now)?.let { event ->
                    QuickLog.i(LOG_TAG, "tap 重新连接 phase=$phase")
                    allowThen { link.user(event) }
                }
            },
            onConfirm = {
                val now = SystemClock.elapsedRealtime()
                QuickLog.i(LOG_TAG, "tap 确认 page=${shown.page} modal=$modal phase=$phase")
                modal = JourneyModal.None
                allowThen { link.user(JourneyEvent.Confirm(now)) }
            },
            onDismiss = {
                modal = JourneyModal.None
                link.user(JourneyEvent.Dismiss)
            },
        )
        return
    }
    ConnectScreen(
        phase = phase,
        passwordSaved = passwordSaved,
        lastCamera = lastCamera,
        onSubmitPassword = { password ->
            val current = phase
            val label = if (current is LinkPhase.Failed && current.failure == LinkFailure.BadPassword) "重试" else "连接"
            QuickLog.i(LOG_TAG, "tap $label with password length=${password.length} phase=$current")
            allowThen { link.submitPassword(password) }
        },
        onConnect = {
            QuickLog.i(LOG_TAG, "tap ${retryLabel(phase)} phase=$phase")
            allowThen { link.connect() }
        },
        onDisconnect = {
            QuickLog.i(LOG_TAG, "tap 断开 phase=$phase")
            link.disconnect()
        },
    )
}
