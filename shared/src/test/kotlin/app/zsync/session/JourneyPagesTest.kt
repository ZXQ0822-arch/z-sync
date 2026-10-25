package app.zsync.session

import app.zsync.ptp.PtpRecovery
import app.zsync.ptp.RecoveryStep
import app.zsync.ptp.recoveryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JourneyPagesTest {
    @Test
    fun aScanOnlyDrivesTheLinkWhenTheAppIsNotListingHotspots() {
        // Listing pages let the person choose; a scan there just refreshes the list.
        assertFalse(scanDrivesTheLink(JourneyPage.FirstHome))
        assertFalse(scanDrivesTheLink(JourneyPage.FirstCantFind))
        assertFalse(scanDrivesTheLink(JourneyPage.SecondReturn))
        assertFalse(scanDrivesTheLink(JourneyPage.SecondCantFind))
        // Trying pages need the scan to decide whether the camera can be joined.
        assertTrue(scanDrivesTheLink(JourneyPage.SecondAuto))
        assertTrue(scanDrivesTheLink(JourneyPage.SecondConnecting))
        assertTrue(scanDrivesTheLink(JourneyPage.FirstConnecting))
        assertTrue(scanDrivesTheLink(JourneyPage.Photos))
        JourneyPage.entries.forEach { page ->
            assertEquals(listsHotspots(page), !scanDrivesTheLink(page), "page=$page")
        }
    }

    @Test
    fun eachNamedPageIsReachedByItsOwnJourney() {
        val reached = linkedMapOf<String, JourneyPage>()
        fun see(state: JourneyState) {
            val file = designFile(state.page)
            val earlier = reached[file]
            if (earlier != null) assertEquals(earlier, state.page)
            reached[file] = state.page
        }

        val home = reduce(initialJourney(null, false), JourneyEvent.Opened(0)).state
        see(home)
        val asking = reduce(
            home.copy(typedPassword = "13579246", seen = listOf("NIKON_Z_30_12345")),
            JourneyEvent.ConnectCamera(0),
        ).state
        assertEquals(JourneyPage.FirstHome, asking.page)
        assertEquals(JourneyModal.WifiOn, asking.modal)
        val dismissed = systemBack(asking)
        assertEquals(JourneyPage.FirstHome, dismissed.state.page)
        assertEquals(JourneyModal.None, dismissed.state.modal)

        val connecting = reduce(asking, JourneyEvent.Confirm(1_000)).state
        see(connecting)
        assertEquals(JourneyPage.FirstConnecting, connecting.page)
        assertEquals(connecting.page, reduce(connecting, JourneyEvent.HotspotAbsent(connecting.generation)).state.page)
        assertEquals(connecting.page, reduce(connecting, JourneyEvent.PtpSessionFailed(connecting.generation)).state.page)
        assertEquals(JourneyPage.FirstCantFind, reduce(connecting, JourneyEvent.NetworkLost(connecting.generation, 1_000)).state.page)

        val missed = reduce(connecting, JourneyEvent.Tick(connecting.deadlineAt!!)).state
        see(missed)
        assertEquals(JourneyPage.FirstCantFind, missed.page)

        val rejected = reduce(connecting, JourneyEvent.AuthRejected(connecting.generation)).state
        see(rejected)
        assertEquals(JourneyPage.FirstPasswordError, rejected.page)
        val homeAgain = reduce(reduce(connecting, JourneyEvent.CancelConnect).state, JourneyEvent.Confirm(2_000)).state
        assertEquals(JourneyPage.FirstHome, homeAgain.page)

        val auto = reduce(
            initialJourney("NIKON_Z_30_12345", true).copy(seen = listOf("NIKON_Z_30_12345")),
            JourneyEvent.Opened(0),
        ).state
        see(auto)
        assertEquals(JourneyPage.SecondAuto, auto.page)
        val returned = reduce(reduce(auto, JourneyEvent.CancelAuto).state, JourneyEvent.Confirm(3_000)).state
        see(returned)
        assertEquals(JourneyPage.SecondReturn, returned.page)

        val manual = reduce(returned, JourneyEvent.ReconnectLast(4_000)).state
        see(manual)
        assertEquals(JourneyPage.SecondConnecting, manual.page)
        assertEquals(JourneyPage.SecondReturn, reduce(reduce(manual, JourneyEvent.CancelConnect).state, JourneyEvent.Confirm(5_000)).state.page)
        val secondMiss = reduce(manual, JourneyEvent.Tick(manual.deadlineAt!!)).state
        see(secondMiss)
        assertEquals(JourneyPage.SecondCantFind, secondMiss.page)
        val secondPassword = reduce(manual, JourneyEvent.AuthRejected(manual.generation)).state
        see(secondPassword)
        assertEquals(JourneyPage.SecondPasswordError, secondPassword.page)

        val photos = JourneyState(page = JourneyPage.Photos, generation = 3, networkReady = true, ptpOpen = true)
        val still = reduce(photos, JourneyEvent.PtpSessionFailed(3)).state
        assertEquals(JourneyPage.Photos, still.page)
        assertEquals("06-photos.html", designFile(still.page))
        assertEquals("正在恢复相机连接", recoveryStatus(PtpRecovery(step = RecoveryStep.WaitingToReopen)))
        assertFalse(joins(still.page))

        assertEquals(
            listOf(
                "01a-home.html",
                "01b-connecting-back.html",
                "01c-cant-find-camera.html",
                "01d-password-error.html",
                "02a-home.html",
                "02a-home-return.html",
                "02a-connecting.html",
                "02c-cant-find-camera.html",
                "02d-password-error.html",
            ),
            reached.keys.toList(),
        )
        assertEquals(reached.size, reached.values.toSet().size)
        assertTrue(reached.values.none { it == JourneyPage.Photos })
    }
}
