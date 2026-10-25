package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkPresentationTest {
    @Test
    fun theMainConnectPhasesUseTheJourneyPages() {
        assertEquals(JourneyPage.FirstHome, presented(LinkPhase.NeedPassword, saved = false).page)
        assertEquals(JourneyPage.FirstHome, presented(LinkPhase.Ready, saved = false).page)
        assertEquals(JourneyPage.SecondReturn, presented(LinkPhase.Ready, saved = true).page)
        assertEquals(JourneyPage.SecondAuto, presented(LinkPhase.Searching(2), saved = true).page)
        assertEquals(JourneyPage.FirstConnecting, presented(LinkPhase.Searching(1), saved = false).page)
        assertEquals(JourneyPage.SecondConnecting, presented(LinkPhase.Connecting, saved = true).page)
        assertEquals(JourneyPage.FirstConnecting, presented(LinkPhase.AwaitingSystemAllow, saved = false).page)
        assertEquals(JourneyPage.SecondCantFind, presented(LinkPhase.WaitingForHotspot, saved = true).page)
        assertEquals(JourneyPage.FirstCantFind, presented(LinkPhase.WaitingForHotspot, saved = false).page)
        assertEquals(JourneyPage.FirstPasswordError, presented(LinkPhase.Failed(LinkFailure.BadPassword), saved = false).page)
        assertEquals(JourneyPage.SecondCantFind, presented(LinkPhase.Failed(LinkFailure.HotspotOff), saved = true).page)
        assertEquals(JourneyPage.SecondCantFind, presented(LinkPhase.Failed(LinkFailure.Dropped), saved = true).page)
        assertEquals(JourneyPage.Photos, presented(LinkPhase.Connected("NIKON_Z_30_12345", true), saved = true).page)
    }

    @Test
    fun wifiUpWithoutPtpStaysOnTheConnectingPage() {
        val phase = LinkPhase.Connected("NIKON_Z_30_12345", true)
        assertTrue(usesJourneyPresentation(phase, mediaReady = false))
        assertEquals(
            JourneyPage.SecondConnecting,
            presentedJourney(phase, true, "NIKON_Z_30_12345", null, mediaReady = false).page,
        )
        assertTrue(journeyChrome(presentedJourney(phase, true, "NIKON_Z_30_12345", null, mediaReady = false)).spinner)
        assertFalse(usesJourneyPresentation(phase, mediaReady = true))
    }

    @Test
    fun oddFailuresStayOnTheExistingScreen() {
        listOf(LinkFailure.OffCamera, LinkFailure.ClientBusy, LinkFailure.Unknown).forEach { failure ->
            assertFalse(usesJourneyPresentation(LinkPhase.Failed(failure)))
        }
        assertFalse(usesJourneyPresentation(LinkPhase.Connected("NIKON_Z_30_12345", false)))
        assertTrue(usesJourneyPresentation(LinkPhase.Searching(1)))
        assertTrue(usesJourneyPresentation(LinkPhase.Failed(LinkFailure.BadPassword)))
    }

    @Test
    fun aSavedCameraIsNamedOnTheAutoPage() {
        val shown = presentedJourney(
            LinkPhase.Searching(1),
            passwordSaved = true,
            savedSsid = "\"NIKON_Z_30_12345\"",
            typedPassword = null,
            modal = JourneyModal.CancelAuto,
        )
        assertEquals(JourneyPage.SecondAuto, shown.page)
        assertEquals("NIKON_Z_30_12345", shown.savedSsid)
        assertEquals(JourneyModal.CancelAuto, shown.modal)
        assertEquals("上次连接 NIKON_Z_30_12345", journeyChrome(shown).lastCamera)
        assertFalse(journeyChrome(shown).spinner)
    }

    @Test
    fun scannedNikonHotspotsShowOnTheReturnPageAndTheConnectingRows() {
        val names = listOf("HOME_NET_01", "\"NIKON_Z_30_12346\"", "NIKON_Z_30_12345", "Canon_R50")
        val back = presentedJourney(LinkPhase.Ready, true, "NIKON_Z_30_12345", null, seen = names)
        assertEquals(listOf("NIKON_Z_30_12346", "NIKON_Z_30_12345"), back.seen)
        assertEquals(listOf("NIKON_Z_30_12346", "NIKON_Z_30_12345"), journeyChrome(back).hotspots)

        val connecting = presentedJourney(LinkPhase.Connecting, true, "NIKON_Z_30_12345", null, seen = names)
        assertEquals(
            listOf("正在连接 NIKON_Z_30_12345", "NIKON_Z_30_12346"),
            journeyChrome(connecting).rows,
        )
        val missed = presentedJourney(LinkPhase.WaitingForHotspot, false, null, null, seen = names)
        assertEquals(emptyList(), journeyChrome(missed).hotspots)
        val home = presentedJourney(LinkPhase.NeedPassword, false, null, null, seen = emptyList())
        assertEquals(emptyList(), journeyChrome(home).hotspots)
    }
}

private fun presented(phase: LinkPhase, saved: Boolean) = presentedJourney(
    phase,
    passwordSaved = saved,
    savedSsid = if (saved) "NIKON_Z_30_12345" else null,
    typedPassword = null,
)
