package app.zsync.ptp

const val PTP_SESSION_NOT_OPEN = 0x2003

fun classifyPtpFailure(error: Throwable): PtpBreak? {
    val message = error.message.orEmpty()
    if (message.contains("camera closed the connection")) return PtpBreak.CameraClosed
    if (message.contains("no data", ignoreCase = true)) return PtpBreak.CameraClosed
    if (error.javaClass.simpleName == "SocketTimeoutException" || message.contains("timed out", ignoreCase = true)) {
        return PtpBreak.TimedOut
    }
    if (message.contains(PTP_SESSION_NOT_OPEN.toString(16)) || message.contains("SessionNotOpen", ignoreCase = true)) {
        return PtpBreak.SessionNotOpen
    }
    return null
}

interface SessionSockets {
    fun closeCommandAndEvent()

    fun open()

    fun keepalive()

    fun noteRelease()
}

class PtpSessionManager(
    private val sockets: SessionSockets,
    private val now: () -> Long,
) {
    var recovery = PtpRecovery()
        private set

    var transaction = 0
        private set

    var sessions = 0
        private set

    fun nextTransaction(): Int {
        check(recovery.step == RecoveryStep.Ready && recovery.sessionOpen && !recovery.busy)
        transaction += 1
        return transaction
    }

    fun eventPulse(): Boolean = recovery.eventReading && recovery.sessionOpen && !recovery.closeRequested

    fun accept(event: RecoveryEvent): List<RecoveryEffect> {
        val next = reduce(recovery, event)
        recovery = next.state
        return next.effects + apply(next.effects)
    }

    private fun apply(effects: List<RecoveryEffect>): List<RecoveryEffect> {
        val extra = mutableListOf<RecoveryEffect>()
        effects.forEach { effect ->
            when (effect) {
                RecoveryEffect.CLOSE_SOCKETS -> sockets.closeCommandAndEvent()
                RecoveryEffect.OPEN_SESSION -> extra += openOnSameNetwork()
                RecoveryEffect.KEEPALIVE -> {
                    check(!recovery.busy)
                    sockets.keepalive()
                }
                RecoveryEffect.RELEASE_LEASE -> sockets.noteRelease()
                else -> Unit
            }
        }
        return extra
    }

    private fun openOnSameNetwork(): List<RecoveryEffect> {
        return try {
            sockets.open()
            sessions += 1
            transaction = 0
            val opened = reduce(recovery, RecoveryEvent.SessionOpened(recovery.generation))
            recovery = opened.state
            opened.effects + apply(opened.effects)
        } catch (failed: Exception) {
            if (classifyPtpFailure(failed) == null && failed.message?.contains("2003") != true) throw failed
            val again = reduce(recovery, RecoveryEvent.SessionFailed(recovery.generation, now()))
            recovery = again.state
            again.effects
        }
    }
}
