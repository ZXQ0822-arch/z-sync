package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionPresentationTest {
    @Test
    fun theUiUsesTheCoordinatorJourneyAndModalOverlay() {
        val state = initialConnection(null, false).copy(
            journey = initialJourney(null, false).copy(page = JourneyPage.FirstHome),
        )
        val shown = journeyForUi(state, JourneyModal.WifiOn)
        assertEquals(JourneyPage.FirstHome, shown.page)
        assertEquals(JourneyModal.WifiOn, shown.modal)
    }

    @Test
    fun oddFailuresStayOnTheLegacyScreen() {
        val state = initialConnection(null, false)
        assertFalse(usesJourneyUi(state, LinkFailure.OffCamera))
        assertTrue(usesJourneyUi(state))
        val onPhotos = state.withOpenSession("NIKON_Z_30_12345", true).copy(
            lease = state.lease.copy(phase = LeasePhase.Held),
        )
        assertFalse(usesJourneyUi(onPhotos))
    }

    @Test
    fun linkPhaseFollowsTheJourneyPageUntilConnected() {
        var state = initialConnection(null, false)
        assertEquals(LinkPhase.NeedPassword, linkPhaseFor(state, false))
        state = state.copy(journey = state.journey.copy(page = JourneyPage.FirstConnecting))
        assertEquals(LinkPhase.Connecting, linkPhaseFor(state, false))
        state = state.withOpenSession("NIKON_Z_30_12345", true)
        assertEquals(LinkPhase.Connected("NIKON_Z_30_12345", true), linkPhaseFor(state, true))
    }
}
