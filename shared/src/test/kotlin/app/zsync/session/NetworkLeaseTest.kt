package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkLeaseTest {
    @Test
    fun theFirstAcquireRequestsOneLeaseAndNeverBindsTheProcess() {
        val asked = reduce(NetworkLease(), LeaseEvent.Acquire("NIKON_Z_30_12345", "aa:bb"))
        assertEquals(LeasePhase.Acquiring, asked.lease.phase)
        assertEquals(1L, asked.lease.generation)
        assertEquals(listOf(LeaseEffect.REQUEST), asked.effects)
        assertNull(asked.forgetSsid)
        assertFalse(asked.lease.bindsProcess)
        assertFalse(usesLeaseSocket(asked.lease))
    }

    @Test
    fun aHeldWifiIsReusedForTheSameCameraWithoutUnregistering() {
        assertTrue(reusesHeldWifi(true, true, "NIKON_Z_30_12345", "aa:bb", "nikon_z_30_12345", "AA:BB"))
        assertTrue(reusesHeldWifi(true, true, "NIKON_Z_30_12345", null, "NIKON_Z_30_12345", "aa:bb"))
        assertFalse(reusesHeldWifi(true, true, "NIKON_Z_30_12345", "aa:bb", "NIKON_Z_30_12345", "11:22"))
        assertFalse(reusesHeldWifi(true, true, "NIKON_Z_30_12345", "aa:bb", "NIKON_Z_30_12346", "aa:bb"))
        assertFalse(reusesHeldWifi(false, true, "NIKON_Z_30_12345", "aa:bb", "NIKON_Z_30_12345", "aa:bb"))
        assertFalse(reusesHeldWifi(true, false, "NIKON_Z_30_12345", "aa:bb", "NIKON_Z_30_12345", "aa:bb"))
    }

    @Test
    fun theSameTargetReusesTheLeaseWithoutReleasingIt() {
        val acquiring = reduce(NetworkLease(), LeaseEvent.Acquire("NIKON_Z_30_12345", "aa:bb")).lease
        val again = reduce(acquiring, LeaseEvent.Acquire("nikon_z_30_12345", null))
        assertEquals(acquiring, again.lease)
        assertEquals(emptyList(), again.effects)

        val held = reduce(acquiring, LeaseEvent.Available(acquiring.generation, 10)).lease
        assertTrue(usesLeaseSocket(held))
        assertEquals(10L, held.networkId)
        val heldAgain = reduce(held, LeaseEvent.Acquire("NIKON_Z_30_12345", "AA:BB"))
        assertEquals(held, heldAgain.lease)
        assertEquals(emptyList(), heldAgain.effects)

        val unknownThenKnown = reduce(NetworkLease(), LeaseEvent.Acquire("NIKON_Z_30_12345", null)).lease
        val filled = reduce(unknownThenKnown, LeaseEvent.Acquire("NIKON_Z_30_12345", "aa:bb"))
        assertEquals(unknownThenKnown, filled.lease)
        assertEquals(emptyList(), filled.effects)
    }

    @Test
    fun aDifferentBssidReplacesTheLeaseOnce() {
        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        val old = held.generation
        val switched = reduce(held, LeaseEvent.Acquire("NIKON_Z_30_12345", "11:22"))
        assertEquals(LeasePhase.Acquiring, switched.lease.phase)
        assertEquals("11:22", switched.lease.target?.bssid)
        assertNull(switched.lease.networkId)
        assertEquals(listOf(LeaseEffect.UNREGISTER, LeaseEffect.REQUEST), switched.effects)
        assertFalse(switched.lease.bindsProcess)

        val stale = reduce(switched.lease, LeaseEvent.Available(old, 10))
        assertEquals(switched.lease, stale.lease)
        assertEquals(emptyList(), stale.effects)
        val fresh = reduce(switched.lease, LeaseEvent.Available(switched.lease.generation, 11))
        assertEquals(LeasePhase.Held, fresh.lease.phase)
        assertEquals(11L, fresh.lease.networkId)
        assertTrue(usesLeaseSocket(fresh.lease))
    }

    @Test
    fun aPtpFailureKeepsTheLeaseAndThePassword() {
        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        val failed = reduce(held, LeaseEvent.PtpFailed)
        assertEquals(held, failed.lease)
        assertEquals(emptyList(), failed.effects)
        assertNull(failed.forgetSsid)
        assertTrue(usesLeaseSocket(failed.lease))
    }

    @Test
    fun stoppingAnAttemptReleasesOnceAndKeepsThePassword() {
        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        listOf(LeaseEvent.AttemptStopped, LeaseEvent.HotspotMissing, LeaseEvent.TimedOut).forEach { event ->
            val stopped = reduce(held, event)
            assertEquals(LeasePhase.Ended, stopped.lease.phase)
            assertEquals(LeaseEnd.AttemptEnded, stopped.lease.ended)
            assertEquals(listOf(LeaseEffect.UNREGISTER), stopped.effects)
            assertNull(stopped.forgetSsid)
            assertFalse(usesLeaseSocket(stopped.lease))
            val repeat = reduce(stopped.lease, event)
            assertEquals(stopped.lease, repeat.lease)
            assertEquals(emptyList(), repeat.effects)
        }
    }

    @Test
    fun authFailureForgetsOnlyThatCameraAndReleasesOnce() {
        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        val rejected = reduce(held, LeaseEvent.AuthRejected(held.generation))
        assertEquals(LeasePhase.Ended, rejected.lease.phase)
        assertEquals(LeaseEnd.AuthRejected, rejected.lease.ended)
        assertEquals("NIKON_Z_30_12345", rejected.forgetSsid)
        assertEquals(listOf(LeaseEffect.UNREGISTER), rejected.effects)

        val again = reduce(rejected.lease, LeaseEvent.AuthRejected(held.generation))
        assertEquals(rejected.lease, again.lease)
        assertEquals(emptyList(), again.effects)
        assertNull(again.forgetSsid)

        val idle = reduce(NetworkLease(), LeaseEvent.AuthRejected(0))
        assertEquals(LeasePhase.Idle, idle.lease.phase)
        assertEquals(emptyList(), idle.effects)
        assertNull(idle.forgetSsid)
    }

    @Test
    fun userCancelAndSessionEndEachReleaseOnce() {
        val acquiring = reduce(NetworkLease(), LeaseEvent.Acquire("NIKON_Z_30_12345", "aa:bb")).lease
        val cancelled = reduce(acquiring, LeaseEvent.UserCancel)
        assertEquals(LeaseEnd.UserConfirmed, cancelled.lease.ended)
        assertEquals(listOf(LeaseEffect.UNREGISTER), cancelled.effects)
        assertNull(cancelled.forgetSsid)
        assertEquals(cancelled.lease, reduce(cancelled.lease, LeaseEvent.UserCancel).lease)
        assertEquals(emptyList(), reduce(cancelled.lease, LeaseEvent.Lost(acquiring.generation, 10)).effects)
        assertEquals(emptyList(), reduce(cancelled.lease, LeaseEvent.Available(acquiring.generation, 10)).effects)

        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        val ended = reduce(held, LeaseEvent.EndSession)
        assertEquals(LeaseEnd.SessionEnded, ended.lease.ended)
        assertEquals(listOf(LeaseEffect.UNREGISTER), ended.effects)
        assertEquals(emptyList(), reduce(ended.lease, LeaseEvent.EndSession).effects)
        assertEquals(emptyList(), reduce(NetworkLease(), LeaseEvent.UserCancel).effects)
        assertEquals(emptyList(), reduce(NetworkLease(), LeaseEvent.EndSession).effects)
    }

    @Test
    fun losingTheCurrentNetworkReleasesOnceAndAReplacementDoesNot() {
        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        val other = reduce(held, LeaseEvent.Lost(held.generation, 9))
        assertEquals(held, other.lease)
        assertEquals(emptyList(), other.effects)

        val replaced = reduce(held, LeaseEvent.Available(held.generation, 11))
        assertEquals(LeasePhase.Held, replaced.lease.phase)
        assertEquals(11L, replaced.lease.networkId)
        assertEquals(emptyList(), replaced.effects)
        val oldGone = reduce(replaced.lease, LeaseEvent.Lost(held.generation, 10))
        assertEquals(replaced.lease, oldGone.lease)
        assertEquals(emptyList(), oldGone.effects)

        val gone = reduce(replaced.lease, LeaseEvent.Lost(replaced.lease.generation, 11))
        assertEquals(LeasePhase.Ended, gone.lease.phase)
        assertEquals(LeaseEnd.NetworkLost, gone.lease.ended)
        assertEquals(listOf(LeaseEffect.UNREGISTER), gone.effects)
        assertNull(gone.forgetSsid)
        assertEquals(emptyList(), reduce(gone.lease, LeaseEvent.Lost(replaced.lease.generation, 11)).effects)
    }

    @Test
    fun anAvailableCallbackIsIgnoredOnceTheLeaseAlreadyHoldsThatNetwork() {
        val held = hold("NIKON_Z_30_12345", "aa:bb", 10)
        val repeat = reduce(held, LeaseEvent.Available(held.generation, 10))
        assertEquals(held, repeat.lease)
        assertEquals(emptyList(), repeat.effects)
        val stale = reduce(held, LeaseEvent.Available(held.generation - 1, 12))
        assertEquals(held, stale.lease)
    }

    @Test
    fun aLostNetworkWhileStillAcquiringReleasesTheRequest() {
        val acquiring = reduce(NetworkLease(), LeaseEvent.Acquire("NIKON_Z_30_12345", null)).lease
        val lost = reduce(acquiring, LeaseEvent.Lost(acquiring.generation, 4))
        assertEquals(LeasePhase.Ended, lost.lease.phase)
        assertEquals(LeaseEnd.NetworkLost, lost.lease.ended)
        assertEquals(listOf(LeaseEffect.UNREGISTER), lost.effects)
        assertNull(lost.forgetSsid)
    }
}

private fun hold(ssid: String, bssid: String, networkId: Long): NetworkLease {
    val acquiring = reduce(NetworkLease(), LeaseEvent.Acquire(ssid, bssid)).lease
    return reduce(acquiring, LeaseEvent.Available(acquiring.generation, networkId)).lease
}
