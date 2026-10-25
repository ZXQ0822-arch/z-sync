package app.zsync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zsync.gallery.PhoneGallery
import app.zsync.log.QuickLog
import app.zsync.gallery.TransferFiles
import app.zsync.link.CameraSessionService
import java.io.File
import app.zsync.session.LinkPhase
import app.zsync.ui.connect.ConnectRoute
import app.zsync.ui.connect.ConnectViewModel
import app.zsync.ui.photos.PhotoRoute
import app.zsync.ui.photos.PhotoViewModel
import app.zsync.ui.theme.ZSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickLog.start(this)
        enableEdgeToEdge()
        setContent {
            ZSyncTheme {
                val connect: ConnectViewModel = viewModel()
                LaunchedEffect(Unit) {
                    connect.onCameraConnected = {
                        // Bring the app back to the front once the camera is connected, so the person
                        // sees the photos page instead of staying on the system Wi-Fi page.
                        startActivity(
                            Intent(this@MainActivity, MainActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                            },
                        )
                    }
                }
                val photos: PhotoViewModel = viewModel(
                    factory = PhotoViewModel.factory(
                        connect.roll,
                        PhoneGallery(contentResolver),
                        TransferFiles(File(cacheDir, "z-sync-transfer")),
                        onConfirmedCancel = { connect.link.disconnect() },
                        onSessionLost = { connect.link.sessionLost() },
                        onSessionText = { text -> CameraSessionService.update(this, text) },
                    ),
                )
                val phase = connect.link.phase.collectAsState().value
                val connection = connect.link.connection.collectAsState().value
                val onCamera = phase as? LinkPhase.Connected
                val mediaReady = connection.connected()
                LaunchedEffect(onCamera, mediaReady) {
                    if (onCamera == null || !mediaReady) {
                        photos.abandonSession()
                        CameraSessionService.stop(this@MainActivity)
                    } else {
                        // Hold the process up while the camera is connected, so backgrounding the
                        // app no longer freezes it and drops the hotspot.
                        CameraSessionService.start(this@MainActivity)
                        photos.start()
                    }
                }
                if (onCamera == null || !mediaReady) {
                    ConnectRoute(connect.link)
                } else {
                    PhotoRoute(internetAvailable = onCamera.internetAvailable, model = photos)
                }
            }
        }
    }
}
