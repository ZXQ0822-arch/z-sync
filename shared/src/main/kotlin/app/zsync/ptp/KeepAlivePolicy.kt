package app.zsync.ptp

/**
 * Decides when the periodic camera keep-alive may fire. Kept pure so the timing rule can be tested
 * without a camera or a coroutine clock.
 *
 * The camera drops its Wi-Fi when the PTP client goes quiet, so the app sends a lightweight
 * `GET_DEVICE_INFO` every [intervalMs]. Sending while a transfer is busy is pointless: the transfer
 * already keeps the link alive, and the roll's lock would refuse the keep-alive anyway. A busy tick
 * therefore does not count as a sent keep-alive, so the heartbeat goes out as soon as work clears.
 */
class KeepAlivePolicy(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastSentAt: Long? = null

    fun shouldSend(now: Long, busy: Boolean): Boolean {
        if (busy) return false
        val last = lastSentAt ?: return true
        return now - last >= intervalMs
    }

    fun markSent(now: Long) {
        lastSentAt = now
    }

    fun reset() {
        lastSentAt = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
    }
}
