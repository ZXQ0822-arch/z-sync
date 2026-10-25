package app.zsync.link

import app.zsync.session.ConnectionState
import app.zsync.session.JourneyEvent
import app.zsync.session.LinkPhase
import kotlinx.coroutines.flow.StateFlow

interface CameraLink {
    val phase: StateFlow<LinkPhase>
    val passwordSaved: StateFlow<Boolean>
    val lastCamera: StateFlow<String?> 
    val seenHotspots: StateFlow<List<String>>
    val connection: StateFlow<ConnectionState>

    fun screenOpened()

    fun user(event: JourneyEvent)

    fun submitPassword(password: String)

    fun connect()

    fun disconnect()

    fun cameraGone()

    /** The photo session died while the camera network may still be up. */
    fun sessionLost()
}
