package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionCoordinatorTest {
    @Test
    fun connectedRequiresPhotosPageHeldLeaseAndOpenSession() {
        var conn = initialConnection(savedSsid = null, hasSavedPassword = false)
        assertFalse(conn.connected())
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Opened(1_000))).state
        val seen = conn.journey.copy(seen = listOf("NIKON_Z_30_12345"), typedPassword = "13579246")
        conn = conn.copy(journey = seen)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.ConnectCamera(1_000))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Confirm(2_000))).state
        assertEquals(62_000, conn.journey.deadlineAt)
        assertFalse(conn.connected())
        assertEquals(LeasePhase.Acquiring, conn.lease.phase)
        val gen = conn.lease.generation
        conn = step(conn, ConnectionEvent.Network(LeaseEvent.Available(gen, 77))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.NetworkAcquired(conn.journey.generation))).state
        assertEquals(SessionPhase.Opening, conn.session)
        assertFalse(conn.connected())
        conn = step(conn, ConnectionEvent.User(JourneyEvent.PtpSessionOpened(conn.journey.generation))).state
        assertEquals(JourneyPage.Photos, conn.journey.page)
        assertEquals(LeasePhase.Held, conn.lease.phase)
        assertEquals(SessionPhase.Open, conn.session)
        assertTrue(conn.connected())
    }

    @Test
    fun withOpenSessionMarksPhotosHeldLeaseAndOpenPtp() {
        var conn = initialConnection("NIKON_Z_30_12345", true)
        conn = conn.copy(lease = conn.lease.copy(phase = LeasePhase.Acquiring))
        conn = conn.withOpenSession("NIKON_Z_30_12345", true)
        assertTrue(conn.connected())
        assertEquals(JourneyPage.Photos, conn.journey.page)
        assertEquals(LeasePhase.Held, conn.lease.phase)
    }

    @Test
    fun sessionLostClosesTheSessionButKeepsTheHeldLease() {
        val conn = onPhotosPage()
        val held = conn.lease
        val next = step(conn, ConnectionEvent.User(JourneyEvent.SessionLost(conn.journey.generation, 1_000)))
        assertEquals(SessionPhase.Closed, next.state.session)
        assertEquals(held, next.state.lease)
        assertEquals(LeasePhase.Held, next.state.lease.phase)
        assertEquals(JourneyPage.SecondAuto, next.state.journey.page)
        assertTrue(next.state.journey.disconnected)
    }

    @Test
    fun ptpFailureDoesNotUnregisterTheHeldLease() {
        var conn = onPhotosPage()
        val held = conn.lease
        val next = step(conn, ConnectionEvent.Network(LeaseEvent.PtpFailed))
        assertEquals(held, next.state.lease)
        assertTrue(next.state.lease.phase == LeasePhase.Held)
        assertEquals(emptyList(), next.leaseEffects)
    }

    @Test
    fun staleLeaseCallbacksLeaveTheCurrentAttemptUntouched() {
        var conn = initialConnection(null, false)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Opened(0))).state
        val oldLeaseGen = conn.lease.generation
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Confirm(1_000))).state
        val stale = step(conn, ConnectionEvent.Network(LeaseEvent.Available(oldLeaseGen, 5)))
        assertEquals(conn.lease, stale.state.lease)
    }

    @Test
    fun confirmingCancelReleasesTheLeaseOnceWithoutJoiningAgain() {
        var conn = initialConnection(null, false)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Opened(0))).state
        val seen = conn.journey.copy(seen = listOf("NIKON_Z_30_12345"), typedPassword = "13579246")
        conn = conn.copy(journey = seen)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.ConnectCamera(1_000))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Confirm(1_000))).state
        val asking = step(conn, ConnectionEvent.User(JourneyEvent.CancelConnect)).state
        assertEquals(JourneyModal.CancelLink, asking.journey.modal)
        val home = step(asking, ConnectionEvent.User(JourneyEvent.Confirm(2_000)))
        assertEquals(JourneyPage.FirstHome, home.state.journey.page)
        assertEquals(LeasePhase.Ended, home.state.lease.phase)
        assertEquals(listOf(LeaseEffect.UNREGISTER), home.leaseEffects.filter { it == LeaseEffect.UNREGISTER })
        val again = step(home.state, ConnectionEvent.User(JourneyEvent.Confirm(2_000)))
        assertEquals(home.state.journey.page, again.state.journey.page)
        assertEquals(LeasePhase.Ended, again.state.lease.phase)
    }

    @Test
    fun theSixtySecondTickEndsTheAttemptWithoutStartingAnotherJoin() {
        var conn = initialConnection(null, false)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Opened(0))).state
        val seen = conn.journey.copy(seen = listOf("NIKON_Z_30_12345"), typedPassword = "13579246")
        conn = conn.copy(journey = seen)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.ConnectCamera(1_000))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Confirm(1_000))).state
        val deadline = conn.journey.deadlineAt ?: error("attempt deadline missing")
        val timed = step(conn, ConnectionEvent.User(JourneyEvent.Tick(deadline)))
        assertEquals(JourneyPage.FirstCantFind, timed.state.journey.page)
        assertEquals(LeasePhase.Ended, timed.state.lease.phase)
        assertTrue(LeaseEffect.UNREGISTER in timed.leaseEffects)
    }

    @Test
    fun networkLostDuringAnAttemptEndsTheLeaseAndGoesToCantFind() {
        var conn = initialConnection(null, false)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Opened(0))).state
        val seen = conn.journey.copy(seen = listOf("NIKON_Z_30_12345"), typedPassword = "13579246")
        conn = conn.copy(journey = seen)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.ConnectCamera(1_000))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Confirm(2_000))).state
        val next = step(conn, ConnectionEvent.User(JourneyEvent.NetworkLost(conn.journey.generation, 3_000)))
        assertEquals(JourneyPage.FirstCantFind, next.state.journey.page)
        assertEquals(LeasePhase.Ended, next.state.lease.phase)
        assertEquals(LeaseEnd.NetworkLost, next.state.lease.ended)
        assertEquals(SessionPhase.Closed, next.state.session)
        assertTrue(LeaseEffect.UNREGISTER in next.leaseEffects)
    }

    private fun onPhotosPage(): ConnectionState {
        var conn = initialConnection(null, false)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Opened(0))).state
        val seen = conn.journey.copy(seen = listOf("NIKON_Z_30_12345"), typedPassword = "13579246")
        conn = conn.copy(journey = seen)
        conn = step(conn, ConnectionEvent.User(JourneyEvent.ConnectCamera(1_000))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.Confirm(1_000))).state
        conn = step(conn, ConnectionEvent.Network(LeaseEvent.Available(conn.lease.generation, 1))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.NetworkAcquired(conn.journey.generation))).state
        conn = step(conn, ConnectionEvent.User(JourneyEvent.PtpSessionOpened(conn.journey.generation))).state
        assertTrue(conn.connected())
        return conn
    }

    private fun step(state: ConnectionState, event: ConnectionEvent): ConnectionTransition {
        return reduce(state, event)
    }
}
