package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceLogTest {
    @Test
    fun oneAttemptSharesATraceIdAcrossLeasePtpRecoveryAndQueue() {
        val trace = "c-19"
        val events = listOf(
            lease(trace, LeaseCallback.Request, generation = 4),
            lease(trace, LeaseCallback.Available, generation = 4, networkId = 10),
            ptp(trace),
            recovery(trace),
            queue(trace),
            TraceEvent.Ended(trace, DisconnectCause.PtpFirst),
        )
        assertTrue(sameTrace(events))
        assertTrue(events.all { render(it).startsWith("trace=$trace") })
    }

    @Test
    fun thePasswordBodyNeverAppearsAndOnlyItsLengthDoes() {
        val secret = "13579246"
        val line = render(lease("c-19", LeaseCallback.Request, generation = 4, passwordLength = passwordLength(secret)))
        assertEquals(8, passwordLength(secret))
        assertTrue(line.contains("passwordLength=8"))
        assertFalse(line.contains(secret))
        assertFalse(render(lease("c-19", LeaseCallback.Lost, generation = 4, passwordLength = null)).contains("passwordLength=null"))
    }

    @Test
    fun leaseCallbacksCarryTheGeneration() {
        listOf(LeaseCallback.Request, LeaseCallback.Available, LeaseCallback.Lost, LeaseCallback.Release).forEach { callback ->
            val line = render(lease("c-19", callback, generation = 7, networkId = 10))
            assertTrue(line.contains("lease=7"))
            assertTrue(line.contains("callback=$callback"))
            assertTrue(line.contains("ssid=NIKON_Z_30_12345"))
            assertTrue(line.contains("bssid=aa:bb"))
            assertTrue(line.contains("journey=SecondAuto"))
            assertTrue(line.contains("surface=Photos"))
        }
    }

    @Test
    fun aPtpLineCarriesTheOperationTransactionHandleAndOffset() {
        val line = render(ptp("c-19"))
        assertTrue(line.contains("operation=GetPartialObject"))
        assertTrue(line.contains("transaction=42"))
        assertTrue(line.contains("handle=1001"))
        assertTrue(line.contains("offset=1048576"))
        assertTrue(line.contains("chunk=1048576"))
    }

    @Test
    fun aRecoveryLineCarriesLevelAttemptReasonAndElapsedTime() {
        val line = render(recovery("c-19"))
        assertTrue(line.contains("recovery=Reopen"))
        assertTrue(line.contains("attempt=2"))
        assertTrue(line.contains("reason=SessionNotOpen"))
        assertTrue(line.contains("elapsedMs=2000"))
    }

    @Test
    fun theFiveEndingsStayDistinct() {
        val lines = DisconnectCause.entries.map { render(TraceEvent.Ended("c-19", it)) }
        assertEquals(lines.size, lines.distinct().size)
        assertEquals(
            listOf(
                "Android onLost",
                "PTP closed while the network lease was still held",
                "person confirmed cancel",
                "process ended and the lease is gone",
                "camera standby removed the hotspot",
            ),
            DisconnectCause.entries.map { causeText(it) },
        )
        val secret = "13579246"
        assertTrue(lines.none { it.contains(secret) })
    }

    @Test
    fun aReceiverKeepsOneTraceAndDropsThePasswordBody() {
        val secret = "13579246"
        val lines = mutableListOf<String>()
        val trace = SessionTrace { lines += it }
        trace.begin()
        val id = trace.id
        trace.record(lease(id, LeaseCallback.Request, generation = 4, passwordLength = passwordLength(secret)))
        trace.record(lease(id, LeaseCallback.Available, generation = 4, networkId = 10, passwordLength = passwordLength(secret)))
        trace.record(lease(id, LeaseCallback.Lost, generation = 4, networkId = 10, passwordLength = null))
        trace.record(lease(id, LeaseCallback.Release, generation = 4, passwordLength = null))
        trace.record(ptp(id))
        trace.record(recovery(id))
        trace.record(queue(id))
        DisconnectCause.entries.forEach { trace.record(TraceEvent.Ended(id, it)) }
        assertEquals(lines, trace.lines())
        assertTrue(sameTrace(trace.events()))
        assertTrue(lines.all { it.startsWith("trace=$id") })
        assertTrue(lines.none { it.contains(secret) })
        assertTrue(lines.any { it.contains("passwordLength=8") })
        assertTrue(lines.any { it.contains("callback=Request") && it.contains("lease=4") })
        assertTrue(lines.any { it.contains("callback=Available") })
        assertTrue(lines.any { it.contains("callback=Lost") })
        assertTrue(lines.any { it.contains("callback=Release") })
        assertTrue(lines.any { it.contains("operation=GetPartialObject") && it.contains("transaction=42") && it.contains("offset=1048576") })
        assertTrue(lines.any { it.contains("recovery=Reopen") && it.contains("attempt=2") && it.contains("elapsedMs=2000") })
        assertEquals(5, lines.count { it.contains("cause=") })
    }

    @Test
    fun aQueueLineCarriesTheSafeOffsetAndResult() {
        val line = render(queue("c-19"))
        assertTrue(line.contains("task=DSC_0001.jpg"))
        assertTrue(line.contains("safeBytes=1048576"))
        assertTrue(line.contains("result=partial"))
    }
}

private fun lease(
    trace: String,
    callback: LeaseCallback,
    generation: Long,
    networkId: Long? = null,
    passwordLength: Int? = 8,
) = TraceEvent.Lease(
    traceId = trace,
    journey = TraceJourney.SecondAuto,
    surface = TraceSurface.Photos,
    linkState = "Held",
    sessionState = "Open",
    callback = callback,
    generation = generation,
    networkId = networkId,
    ssid = "NIKON_Z_30_12345",
    bssid = "aa:bb",
    passwordLength = passwordLength,
)

private fun ptp(trace: String) = TraceEvent.Ptp(
    traceId = trace,
    operation = "GetPartialObject",
    transactionId = 42,
    handle = 1001,
    offset = 1_048_576,
    chunkBytes = 1_048_576,
)

private fun recovery(trace: String) = TraceEvent.Recovery(
    traceId = trace,
    level = "Reopen",
    attempt = 2,
    reason = "SessionNotOpen",
    elapsedMs = 2_000,
)

private fun queue(trace: String) = TraceEvent.Queue(
    traceId = trace,
    taskId = "DSC_0001.jpg",
    safeBytes = 1_048_576,
    result = "partial",
)
