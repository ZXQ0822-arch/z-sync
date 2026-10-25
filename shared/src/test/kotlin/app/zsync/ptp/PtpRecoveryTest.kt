package app.zsync.ptp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtpRecoveryTest {
    @Test
    fun aClosedOrTimedOutReadRetriesOnceWithoutReleasingTheNetwork() {
        listOf(PtpBreak.CameraClosed, PtpBreak.TimedOut).forEach { reason ->
            val failed = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, reason, now = 0, generation = 0))
            assertEquals(RecoveryStep.RetryingRead, failed.state.step)
            assertEquals(listOf(RecoveryEffect.RETRY_READ), failed.effects)
            assertTrue(failed.state.sessionOpen)
            assertTrue(failed.state.onPhotos)
            assertTrue(failed.state.thumbnailsKept)
            assertEquals(2_048L, failed.state.offset)
            assertNull(recoveryStatus(failed.state))
            assertNull(recoveryDetail(failed.state))
            assertFalse(failed.forgetPassword)
            assertNull(recoveryStatus(failed.state))
        }
    }

    @Test
    fun theRetriedReadReturnsToReadyAndCanRetryALaterRead() {
        val retrying = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.CameraClosed, 0, 0)).state
        val ok = reduce(retrying, RecoveryEvent.ReadOk(retrying.generation))
        assertEquals(RecoveryStep.Ready, ok.state.step)
        assertFalse(ok.state.readRetried)
        val again = reduce(ok.state, RecoveryEvent.Failed(PtpKind.Read, PtpBreak.TimedOut, 20, ok.state.generation))
        assertEquals(listOf(RecoveryEffect.RETRY_READ), again.effects)
    }

    @Test
    fun sessionNotOpenSkipsTheReadRetryAndWaitsOneSecond() {
        val failed = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, now = 50, generation = 0))
        assertEquals(RecoveryStep.WaitingToReopen, failed.state.step)
        assertEquals(listOf(RecoveryEffect.PAUSE_QUEUE, RecoveryEffect.CLOSE_SOCKETS), failed.effects)
        assertFalse(failed.state.sessionOpen)
        assertFalse(failed.state.eventReading)
        assertTrue(failed.state.queuePaused)
        assertTrue(failed.state.onPhotos)
        assertEquals(1_050L, failed.state.waitUntil)
        assertEquals("正在恢复相机连接", recoveryStatus(failed.state))
        assertEquals("正在同一网络上重建会话", recoveryDetail(failed.state))
        assertFalse(failed.forgetPassword)
    }

    @Test
    fun aSecondReadFailureReopensOnTheSameClock() {
        val first = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.CameraClosed, 0, 0)).state
        val second = reduce(first, RecoveryEvent.Failed(PtpKind.Read, PtpBreak.TimedOut, now = 10, generation = first.generation))
        assertEquals(RecoveryStep.WaitingToReopen, second.state.step)
        assertEquals(1_010L, second.state.waitUntil)
        assertEquals(listOf(RecoveryEffect.PAUSE_QUEUE, RecoveryEffect.CLOSE_SOCKETS), second.effects)
        assertEquals(2_048L, second.state.offset)
        assertFalse(second.effects.contains(RecoveryEffect.RELEASE_LEASE))
    }

    @Test
    fun reopenWaitsOneThenTwoThenFourSecondsAndOnlyOpensOncePerWait() {
        var state = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, now = 0, generation = 0)).state
        val opens = mutableListOf<Long>()
        listOf(1_000L to 0, 3_000L to 1, 7_000L to 2).forEach { (due, slot) ->
            val early = reduce(state, RecoveryEvent.Tick(due - 1))
            assertEquals(state, early.state)
            assertEquals(emptyList(), early.effects)
            val opened = reduce(state, RecoveryEvent.Tick(due))
            assertEquals(RecoveryStep.OpeningSession, opened.state.step)
            assertEquals(listOf(RecoveryEffect.OPEN_SESSION), opened.effects)
            assertEquals(slot, opened.state.reopenSlot)
            val repeat = reduce(opened.state, RecoveryEvent.Tick(due + 10))
            assertEquals(opened.state, repeat.state)
            opens += due
            if (slot < 2) {
                val failed = reduce(opened.state, RecoveryEvent.SessionFailed(opened.state.generation, due))
                assertEquals(RecoveryStep.WaitingToReopen, failed.state.step)
                assertEquals(due + REOPEN_WAITS_MS[slot + 1], failed.state.waitUntil)
                assertEquals(emptyList(), failed.effects)
                state = failed.state
            } else {
                val givenUp = reduce(opened.state, RecoveryEvent.SessionFailed(opened.state.generation, due))
                assertEquals(RecoveryStep.NeedsPerson, givenUp.state.step)
                assertEquals("继续连接" to "取消连接", recoveryChoices(givenUp.state))
                assertTrue(givenUp.state.onPhotos)
                assertTrue(givenUp.state.thumbnailsKept)
                assertFalse(givenUp.forgetPassword)
                assertNull(givenUp.state.waitUntil)
            }
        }
        assertEquals(listOf(1_000L, 3_000L, 7_000L), opens)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), REOPEN_WAITS_MS)
    }

    @Test
    fun aFailureDuringReopenDoesNotStartASecondRound() {
        val waiting = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, 0, 0)).state
        val extra = reduce(waiting, RecoveryEvent.Failed(PtpKind.Read, PtpBreak.CameraClosed, 100, waiting.generation))
        assertEquals(waiting, extra.state)
        assertEquals(emptyList(), extra.effects)
        val opening = reduce(waiting, RecoveryEvent.Tick(1_000)).state
        val during = reduce(opening, RecoveryEvent.Failed(PtpKind.Read, PtpBreak.TimedOut, 1_100, opening.generation))
        assertEquals(opening, during.state)
        assertEquals(emptyList(), during.effects)
    }

    @Test
    fun aReopenedSessionResumesThePausedQueue() {
        val waiting = reduce(ready(offset = 9_000), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, 0, 0)).state
        val opening = reduce(waiting, RecoveryEvent.Tick(1_000)).state
        val resumed = reduce(opening, RecoveryEvent.SessionOpened(opening.generation))
        assertEquals(RecoveryStep.Ready, resumed.state.step)
        assertTrue(resumed.state.sessionOpen)
        assertTrue(resumed.state.eventReading)
        assertFalse(resumed.state.queuePaused)
        assertEquals(9_000L, resumed.state.offset)
        assertEquals(listOf(RecoveryEffect.RESUME_QUEUE), resumed.effects)
        assertEquals(emptyList(), reduce(resumed.state, RecoveryEvent.SessionOpened(opening.generation)).effects)
    }

    @Test
    fun deleteIsInspectedWhenTheSocketStillAnswersAndReopenedWhenItDoesNot() {
        val timedOut = reduce(ready(), RecoveryEvent.Failed(PtpKind.Delete, PtpBreak.TimedOut, 0, 0))
        assertEquals(RecoveryStep.Ready, timedOut.state.step)
        assertEquals(listOf(RecoveryEffect.INSPECT_OBJECT), timedOut.effects)
        assertFalse(timedOut.effects.contains(RecoveryEffect.RETRY_READ))
        assertFalse(timedOut.forgetPassword)

        val closed = reduce(ready(), RecoveryEvent.Failed(PtpKind.Delete, PtpBreak.CameraClosed, 5, 0))
        assertEquals(RecoveryStep.WaitingToReopen, closed.state.step)
        assertEquals(listOf(RecoveryEffect.PAUSE_QUEUE, RecoveryEffect.CLOSE_SOCKETS), closed.effects)

        val notOpen = reduce(ready(), RecoveryEvent.Failed(PtpKind.Delete, PtpBreak.SessionNotOpen, 5, 0))
        assertEquals(RecoveryStep.WaitingToReopen, notOpen.state.step)
        assertFalse(notOpen.effects.contains(RecoveryEffect.INSPECT_OBJECT))
    }

    @Test
    fun networkLossReacquiresWithoutForgettingThePasswordAndIgnoresTheOldSession() {
        val waiting = reduce(ready(offset = 100), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, 0, 0)).state
        val lost = reduce(waiting, RecoveryEvent.Lost(waiting.generation))
        assertEquals(RecoveryStep.ReacquiringNetwork, lost.state.step)
        assertEquals(listOf(RecoveryEffect.PAUSE_QUEUE, RecoveryEffect.SCAN, RecoveryEffect.REACQUIRE_LEASE), lost.effects)
        assertFalse(lost.forgetPassword)
        assertTrue(lost.state.onPhotos)
        assertTrue(lost.state.thumbnailsKept)
        assertEquals(100L, lost.state.offset)
        assertFalse(lost.effects.contains(RecoveryEffect.RELEASE_LEASE))

        val stale = reduce(lost.state, RecoveryEvent.SessionOpened(waiting.generation))
        assertEquals(lost.state, stale.state)
        assertEquals(emptyList(), stale.effects)
        val again = reduce(lost.state, RecoveryEvent.Lost(lost.state.generation))
        assertEquals(lost.state, again.state)
        assertEquals(emptyList(), again.effects)

        val ready = reduce(lost.state, RecoveryEvent.NetworkReady(lost.state.generation))
        assertEquals(RecoveryStep.OpeningSession, ready.state.step)
        assertEquals(listOf(RecoveryEffect.OPEN_SESSION), ready.effects)
    }

    @Test
    fun thePersonCanStartOneNewAttemptOrCancelOnce() {
        val stuck = needsPerson()
        assertEquals("正在恢复相机连接", recoveryStatus(stuck))
        val ignored = reduce(stuck, RecoveryEvent.Continue)
        assertEquals(RecoveryStep.Ready, ignored.state.step)
        assertEquals(listOf(RecoveryEffect.START_ATTEMPT), ignored.effects)
        assertTrue(ignored.state.onPhotos)
        assertFalse(ignored.forgetPassword)
        assertEquals(emptyList(), reduce(ignored.state, RecoveryEvent.Continue).effects)

        val cancelled = reduce(stuck, RecoveryEvent.Cancel)
        assertEquals(RecoveryStep.Ended, cancelled.state.step)
        assertFalse(cancelled.state.onPhotos)
        assertEquals(listOf(RecoveryEffect.RELEASE_LEASE, RecoveryEffect.CLOSE_SOCKETS, RecoveryEffect.CLEAR_QUEUE), cancelled.effects)
        assertFalse(cancelled.forgetPassword)
        assertEquals(cancelled.state, reduce(cancelled.state, RecoveryEvent.Cancel).state)
        assertEquals(emptyList(), reduce(cancelled.state, RecoveryEvent.Cancel).effects)
        assertEquals(emptyList(), reduce(cancelled.state, RecoveryEvent.Lost(stuck.generation)).effects)
    }

    @Test
    fun closeWaitsForTheCurrentTransactionAndKeepaliveStaysQuiet() {
        val busy = reduce(ready(), RecoveryEvent.TransactionBegan).state
        assertTrue(busy.busy)
        val asked = reduce(busy, RecoveryEvent.CloseAsked)
        assertEquals(emptyList(), asked.effects)
        assertTrue(asked.state.closeRequested)
        assertTrue(asked.state.sessionOpen)
        val repeat = reduce(asked.state, RecoveryEvent.CloseAsked)
        assertEquals(asked.state, repeat.state)
        val blocked = reduce(asked.state, RecoveryEvent.TransactionBegan)
        assertEquals(asked.state, blocked.state)
        assertEquals(emptyList(), reduce(asked.state, RecoveryEvent.Keepalive).effects)

        val closed = reduce(asked.state, RecoveryEvent.TransactionEnded)
        assertEquals(listOf(RecoveryEffect.CLOSE_SOCKETS), closed.effects)
        assertFalse(closed.state.sessionOpen)
        assertFalse(closed.state.busy)
        assertFalse(closed.state.eventReading)

        val idle = ready()
        assertEquals(listOf(RecoveryEffect.KEEPALIVE), reduce(idle, RecoveryEvent.Keepalive).effects)
        val immediate = reduce(idle, RecoveryEvent.CloseAsked)
        assertEquals(listOf(RecoveryEffect.CLOSE_SOCKETS), immediate.effects)
        val recovering = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, 0, 0)).state
        assertEquals(emptyList(), reduce(recovering, RecoveryEvent.Keepalive).effects)
        assertEquals(emptyList(), reduce(recovering, RecoveryEvent.CloseAsked).effects)
    }

    @Test
    fun aStaleFailureCannotStartRecoveryOnANewerGeneration() {
        val lost = reduce(ready(), RecoveryEvent.Lost(0))
        val stale = reduce(lost.state, RecoveryEvent.Failed(PtpKind.Read, PtpBreak.CameraClosed, 10, generation = 0))
        assertEquals(lost.state, stale.state)
        assertEquals(emptyList(), stale.effects)
    }
}

private fun ready(offset: Long = 2_048) = PtpRecovery(offset = offset)

private fun needsPerson(): PtpRecovery {
    var state = reduce(ready(), RecoveryEvent.Failed(PtpKind.Read, PtpBreak.SessionNotOpen, 0, 0)).state
    listOf(1_000L, 3_000L, 7_000L).forEach { due ->
        val opening = reduce(state, RecoveryEvent.Tick(due)).state
        state = reduce(opening, RecoveryEvent.SessionFailed(opening.generation, due)).state
    }
    return state
}
