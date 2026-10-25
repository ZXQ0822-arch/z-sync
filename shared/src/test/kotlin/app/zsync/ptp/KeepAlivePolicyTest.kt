package app.zsync.ptp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAlivePolicyTest {
    @Test
    fun theFirstIdleTickSends() {
        val policy = KeepAlivePolicy(intervalMs = 30_000)
        assertTrue(policy.shouldSend(now = 0, busy = false))
        assertTrue(policy.shouldSend(now = 1_000, busy = false))
    }

    @Test
    fun itWaitsForTheWholeIntervalAfterSending() {
        val policy = KeepAlivePolicy(intervalMs = 30_000)
        policy.markSent(now = 10_000)
        assertFalse(policy.shouldSend(now = 10_000, busy = false))
        assertFalse(policy.shouldSend(now = 39_999, busy = false))
        assertTrue(policy.shouldSend(now = 40_000, busy = false))
        assertTrue(policy.shouldSend(now = 40_001, busy = false))
    }

    @Test
    fun aBusyTickNeverSends() {
        val policy = KeepAlivePolicy(intervalMs = 30_000)
        assertFalse(policy.shouldSend(now = 0, busy = true))
        policy.markSent(now = 0)
        assertFalse(policy.shouldSend(now = 60_000, busy = true))
    }

    @Test
    fun aBusyTickDoesNotConsumeTheInterval() {
        val policy = KeepAlivePolicy(intervalMs = 30_000)
        policy.markSent(now = 0)
        // A long download keeps the policy busy well past the interval.
        assertFalse(policy.shouldSend(now = 45_000, busy = true))
        // The heartbeat goes out as soon as the download clears, not 30s later.
        assertTrue(policy.shouldSend(now = 45_001, busy = false))
    }

    @Test
    fun resettingSendsAgainImmediately() {
        val policy = KeepAlivePolicy(intervalMs = 30_000)
        policy.markSent(now = 10_000)
        assertFalse(policy.shouldSend(now = 20_000, busy = false))
        policy.reset()
        assertTrue(policy.shouldSend(now = 20_000, busy = false))
    }

    @Test
    fun theDefaultIntervalIsThirtySeconds() {
        assertTrue(KeepAlivePolicy.DEFAULT_INTERVAL_MS == 30_000L)
    }
}
