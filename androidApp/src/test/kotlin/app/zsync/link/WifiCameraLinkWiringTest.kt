package app.zsync.link

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.zsync.model.newInitiatorGuid
import app.zsync.session.JourneyPage
import app.zsync.session.LinkEvent
import app.zsync.session.LinkPhase
import app.zsync.session.LinkState
import app.zsync.session.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the connection wiring. These drive [WifiCameraLink] through the real event
 * order (Joined -> Internet) with a fake transport, and pin down the two defects that left the
 * app stuck on the "正在自动连接" screen:
 *
 * 1. OPEN_PTP used to be dropped while the legacy LinkMachine was still Connecting, so PTP was
 *    never opened and the coordinator never reached the Photos page.
 * 2. A failed PTP open used to disarm the 60 second attempt deadline, so the attempt could never
 *    time out to the "找不到相机" page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WifiCameraLinkWiringTest {
    private class FakeStore(override val ssid: String?, override val password: String?) : LinkStore {
        override fun save(ssid: String?, password: String) = Unit

        override fun clearPassword() = Unit

        override fun guid(): ByteArray = newInitiatorGuid()
    }

    private fun link(warmResult: () -> Boolean): WifiCameraLink = WifiCameraLink(
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
        store = FakeStore("NIKON_Z_30_12345", "13579246"),
        wifi = AndroidCameraWifi(ApplicationProvider.getApplicationContext()),
        onWarmPtp = { warmResult() },
    )

    @Test
    fun ptpOpensAsSoonAsTheWifiLeaseArrives() {
        var warmed = false
        val link = link { warmed = true; true }
        link.screenOpened()
        setMachine(link, LinkState(phase = LinkPhase.Connecting, targetSsid = "NIKON_Z_30_12345"))
        dispatch(link, LinkEvent.Joined)
        idle(300)
        assertTrue(warmed, "PTP was never opened after the Wi-Fi lease arrived")
        assertEquals(SessionPhase.Open, link.connection.value.session)
        assertEquals(JourneyPage.Photos, link.connection.value.journey.page)
    }

    @Test
    fun theAttemptTimerStartsBeforeTheCameraIsFound() {
        val link = link { true }
        link.screenOpened()
        val timer = deadlineJob(link)
        assertNotNull(timer, "the 60s attempt must start when the attempt begins, not when the camera answers")
        assertTrue(timer.isActive)
    }

    @Test
    fun aFailedPtpOpenStillTimesOutToTheMissPage() {
        var warmed = false
        val link = link { warmed = true; false }
        link.screenOpened()
        setMachine(link, LinkState(phase = LinkPhase.Connecting, targetSsid = "NIKON_Z_30_12345"))
        dispatch(link, LinkEvent.Joined)
        idle(300)
        assertTrue(warmed)
        assertEquals(JourneyPage.SecondAuto, link.connection.value.journey.page)
        val timer = deadlineJob(link)
        assertNotNull(timer)
        assertTrue(timer.isActive, "the 60s deadline must stay armed after a failed PTP open")
        dispatch(link, LinkEvent.AttemptExpired)
        assertEquals(JourneyPage.SecondCantFind, link.connection.value.journey.page)
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(ms)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setMachine(link: WifiCameraLink, state: LinkState) {
        val field = WifiCameraLink::class.java.getDeclaredField("machine")
        field.isAccessible = true
        field.set(link, state)
    }

    private fun dispatch(link: WifiCameraLink, event: LinkEvent) {
        val method = WifiCameraLink::class.java.getDeclaredMethod("dispatch", LinkEvent::class.java)
        method.isAccessible = true
        method.invoke(link, event)
    }

    private fun deadlineJob(link: WifiCameraLink): Job? {
        val field = WifiCameraLink::class.java.getDeclaredField("deadlineJob")
        field.isAccessible = true
        return field.get(link) as Job?
    }
}
