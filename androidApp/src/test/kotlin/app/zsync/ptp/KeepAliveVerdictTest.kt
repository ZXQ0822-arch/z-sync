package app.zsync.ptp

import app.zsync.ptp.KeepAliveOutcome.Dead
import app.zsync.ptp.KeepAliveOutcome.Sent
import app.zsync.ptp.KeepAliveOutcome.Skipped
import app.zsync.ptp.KeepAliveOutcome.Timeout
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class KeepAliveVerdictTest {
    @Test
    fun aClosedSocketIsGoneOnTheFirstReport() {
        val verdict = KeepAliveVerdict()

        assertTrue(verdict.lost(Dead))
    }

    @Test
    fun oneTimeoutIsNotEnoughBecauseTheCameraMayAnswerLate() {
        val verdict = KeepAliveVerdict()

        assertFalse(verdict.lost(Timeout))
        assertTrue(verdict.lost(Timeout))
    }

    @Test
    fun anAnswerBetweenTimeoutsClearsTheStreak() {
        val verdict = KeepAliveVerdict()

        assertFalse(verdict.lost(Timeout))
        assertFalse(verdict.lost(Sent))
        assertFalse(verdict.lost(Timeout))
        assertTrue(verdict.lost(Timeout))
    }

    @Test
    fun aSkippedHeartbeatSaysNothingSoItDoesNotAccumulate() {
        val verdict = KeepAliveVerdict()

        assertFalse(verdict.lost(Timeout))
        assertFalse(verdict.lost(Skipped))
        assertFalse(verdict.lost(Timeout))
    }

    @Test
    fun resetForgetsAPartialStreak() {
        val verdict = KeepAliveVerdict()

        assertFalse(verdict.lost(Timeout))
        verdict.reset()

        assertFalse(verdict.lost(Timeout))
        assertTrue(verdict.lost(Timeout))
    }

    @Test
    fun theThresholdCanBeTighter() {
        val verdict = KeepAliveVerdict(timeoutsToGiveUp = 1)

        assertTrue(verdict.lost(Timeout))
    }
}
