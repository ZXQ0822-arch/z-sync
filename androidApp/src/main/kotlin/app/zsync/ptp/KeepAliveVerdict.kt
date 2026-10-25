package app.zsync.ptp

/** What one idle heartbeat found on the wire. */
enum class KeepAliveOutcome {
    /** The camera answered. */
    Sent,

    /** No session, or a transfer held the lock, so nothing went out. Says nothing either way. */
    Skipped,

    /** The camera did not answer in time. It may simply be slow. */
    Timeout,

    /** The socket is gone: the ROM closed it, or the camera did. Nothing can be sent again. */
    Dead,
}

/**
 * Turns heartbeat outcomes into one answer: has the camera session gone?
 *
 * A closed socket is final on the first report: ColorOS destroys the sockets of the app it freezes,
 * so the next `GET_DEVICE_INFO` fails with `ECONNABORTED` and no later call can work. A timeout is
 * not final, because a busy camera can answer late; it takes two in a row to give up. This is the
 * only thing that reports a dead camera when the system never calls `onLost`.
 */
class KeepAliveVerdict(private val timeoutsToGiveUp: Int = 2) {
    private var timeouts = 0

    fun lost(outcome: KeepAliveOutcome): Boolean {
        when (outcome) {
            KeepAliveOutcome.Sent, KeepAliveOutcome.Skipped -> timeouts = 0
            KeepAliveOutcome.Timeout -> timeouts += 1
            KeepAliveOutcome.Dead -> {
                timeouts = 0
                return true
            }
        }
        return timeouts >= timeoutsToGiveUp
    }

    fun reset() {
        timeouts = 0
    }
}
