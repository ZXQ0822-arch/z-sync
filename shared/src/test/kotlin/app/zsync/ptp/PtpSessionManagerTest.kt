package app.zsync.ptp

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PtpSessionManagerTest {
    @Test
    fun closedTimeoutAndSessionNotOpenUseOneBoundedRecovery() {
        listOf(
            IllegalStateException("camera closed the connection"),
            SocketTimeoutException("connect timed out"),
            IllegalStateException("ptp 1009 -> 2003"),
            IOException("ptp returned no data for chunk offset=1048576"),
        ).forEach { error ->
            val sockets = FakeSockets()
            val manager = PtpSessionManager(sockets, now = { 0 })
            val reason = classifyPtpFailure(error)
            val effects = manager.accept(RecoveryEvent.Failed(PtpKind.Read, reason!!, now = 0, generation = 0))
            assertFalse(effects.contains(RecoveryEffect.RELEASE_LEASE))
            assertEquals(0, sockets.releases)
            assertTrue(manager.recovery.onPhotos)
            assertTrue(manager.recovery.thumbnailsKept)
            if (reason == PtpBreak.SessionNotOpen) {
                assertEquals(RecoveryStep.WaitingToReopen, manager.recovery.step)
                assertEquals(1, sockets.closes)
            } else {
                assertEquals(RecoveryStep.RetryingRead, manager.recovery.step)
                assertEquals(0, sockets.closes)
            }
        }
    }

    @Test
    fun aFailedOpenWaitsThenRebuildsTheSessionWithoutReleasingWifi() {
        val sockets = FakeSockets(openResults = ArrayDeque(listOf("SessionNotOpen", "ok")))
        var clock = 0L
        val manager = PtpSessionManager(sockets, now = { clock })
        manager.accept(RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, 0, 0))
        val ignored = manager.accept(RecoveryEvent.Failed(PtpKind.Read, PtpBreak.CameraClosed, 10, manager.recovery.generation))
        assertEquals(emptyList(), ignored)
        assertEquals(1, sockets.closes)

        clock = 999
        assertEquals(emptyList(), manager.accept(RecoveryEvent.Tick(clock)))
        clock = 1_000
        manager.accept(RecoveryEvent.Tick(clock))
        assertEquals(RecoveryStep.WaitingToReopen, manager.recovery.step)
        assertEquals(3_000L, manager.recovery.waitUntil)

        clock = 3_000
        val resumed = manager.accept(RecoveryEvent.Tick(clock))
        assertTrue(resumed.contains(RecoveryEffect.RESUME_QUEUE))
        assertEquals(RecoveryStep.Ready, manager.recovery.step)
        assertEquals(1, manager.sessions)
        assertEquals(0, manager.transaction)
        assertEquals(1, manager.nextTransaction())
        assertEquals(0, sockets.releases)
        assertTrue(manager.eventPulse())
    }

    @Test
    fun closeWaitsForTheCommandAndKeepaliveStaysOffTheCommand() {
        val sockets = FakeSockets()
        val manager = PtpSessionManager(sockets, now = { 0 })
        manager.accept(RecoveryEvent.TransactionBegan)
        assertTrue(manager.eventPulse())
        assertEquals(emptyList(), manager.accept(RecoveryEvent.Keepalive))
        assertEquals(0, sockets.keepalives)
        val deferred = manager.accept(RecoveryEvent.CloseAsked)
        assertFalse(deferred.contains(RecoveryEffect.CLOSE_SOCKETS))
        assertEquals(0, sockets.closes)
        val ended = manager.accept(RecoveryEvent.TransactionEnded)
        assertTrue(ended.contains(RecoveryEffect.CLOSE_SOCKETS))
        assertEquals(1, sockets.closes)
        assertFalse(manager.eventPulse())
    }

    @Test
    fun onlyNetworkLossAsksToReacquireTheLease() {
        val sockets = FakeSockets()
        val manager = PtpSessionManager(sockets, now = { 0 })
        val lost = manager.accept(RecoveryEvent.Lost(0))
        assertTrue(lost.contains(RecoveryEffect.REACQUIRE_LEASE))
        assertFalse(lost.contains(RecoveryEffect.RELEASE_LEASE))
        assertEquals(0, sockets.releases)
    }
}

private class FakeSockets(
    private val openResults: ArrayDeque<String> = ArrayDeque(),
) : SessionSockets {
    var closes = 0
    var releases = 0
    var keepalives = 0

    override fun closeCommandAndEvent() {
        closes += 1
    }

    override fun open() {
        val next = openResults.removeFirstOrNull() ?: return
        if (next == "SessionNotOpen") error("ptp 1001 -> 2003")
    }

    override fun keepalive() {
        keepalives += 1
    }

    override fun noteRelease() {
        releases += 1
    }
}
