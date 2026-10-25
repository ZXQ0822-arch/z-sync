package app.zsync.session

enum class LeasePhase {
    Idle,
    Acquiring,
    Held,
    Ended,
}

enum class LeaseEnd {
    UserConfirmed,
    SessionEnded,
    NetworkLost,
    AttemptEnded,
    AuthRejected,
}

enum class LeaseEffect {
    REQUEST,
    UNREGISTER,
}

data class LeaseTarget(
    val ssid: String,
    val bssid: String?,
)

data class NetworkLease(
    val phase: LeasePhase = LeasePhase.Idle,
    val generation: Long = 0,
    val target: LeaseTarget? = null,
    val networkId: Long? = null,
    val ended: LeaseEnd? = null,
    val bindsProcess: Boolean = false,
)

sealed interface LeaseEvent {
    data class Acquire(val ssid: String, val bssid: String?) : LeaseEvent

    data class Available(val generation: Long, val networkId: Long) : LeaseEvent

    data class Lost(val generation: Long, val networkId: Long) : LeaseEvent

    data object PtpFailed : LeaseEvent

    data object AttemptStopped : LeaseEvent

    data object HotspotMissing : LeaseEvent

    data object TimedOut : LeaseEvent

    data class AuthRejected(val generation: Long) : LeaseEvent

    data object UserCancel : LeaseEvent

    data object EndSession : LeaseEvent
}

data class LeaseTransition(
    val lease: NetworkLease,
    val effects: List<LeaseEffect> = emptyList(),
    val forgetSsid: String? = null,
)

fun usesLeaseSocket(lease: NetworkLease): Boolean {
    return lease.phase == LeasePhase.Held && lease.networkId != null && !lease.bindsProcess
}

fun reusesHeldWifi(
    holding: Boolean,
    hasNetwork: Boolean,
    heldSsid: String?,
    heldBssid: String?,
    ssid: String?,
    bssid: String?,
): Boolean {
    if (!holding || !hasNetwork || ssid.isNullOrBlank() || heldSsid.isNullOrBlank()) return false
    if (!heldSsid.equals(ssid, ignoreCase = true)) return false
    val left = heldBssid?.trim()?.takeIf { it.isNotEmpty() }
    val right = bssid?.trim()?.takeIf { it.isNotEmpty() }
    return left == null || right == null || left.equals(right, ignoreCase = true)
}

fun reduce(lease: NetworkLease, event: LeaseEvent): LeaseTransition {
    return when (event) {
        is LeaseEvent.Acquire -> onAcquire(lease, event.ssid, event.bssid)
        is LeaseEvent.Available -> onAvailable(lease, event.generation, event.networkId)
        is LeaseEvent.Lost -> onLost(lease, event.generation, event.networkId)
        LeaseEvent.PtpFailed -> stay(lease)
        LeaseEvent.AttemptStopped, LeaseEvent.HotspotMissing, LeaseEvent.TimedOut ->
            end(lease, LeaseEnd.AttemptEnded, forget = false)
        is LeaseEvent.AuthRejected -> onAuth(lease, event.generation)
        LeaseEvent.UserCancel -> end(lease, LeaseEnd.UserConfirmed, forget = false)
        LeaseEvent.EndSession -> end(lease, LeaseEnd.SessionEnded, forget = false)
    }
}

private fun onAcquire(lease: NetworkLease, ssid: String, bssid: String?): LeaseTransition {
    val target = LeaseTarget(ssid.trim(), bssid?.trim()?.takeIf { it.isNotEmpty() })
    val current = lease.target
    if ((lease.phase == LeasePhase.Acquiring || lease.phase == LeasePhase.Held) && current != null && sameCamera(current, target)) {
        return stay(lease)
    }
    val replacing = lease.phase == LeasePhase.Acquiring || lease.phase == LeasePhase.Held
    return LeaseTransition(
        lease.copy(
            phase = LeasePhase.Acquiring,
            generation = lease.generation + 1,
            target = target,
            networkId = null,
            ended = null,
            bindsProcess = false,
        ),
        listOfNotNull(if (replacing) LeaseEffect.UNREGISTER else null, LeaseEffect.REQUEST),
    )
}

private fun onAvailable(lease: NetworkLease, generation: Long, networkId: Long): LeaseTransition {
    if (generation != lease.generation || (lease.phase != LeasePhase.Acquiring && lease.phase != LeasePhase.Held)) {
        return stay(lease)
    }
    if (lease.networkId == networkId) return stay(lease)
    return stay(lease.copy(phase = LeasePhase.Held, networkId = networkId, bindsProcess = false))
}

private fun onLost(lease: NetworkLease, generation: Long, networkId: Long): LeaseTransition {
    if (generation != lease.generation || (lease.phase != LeasePhase.Acquiring && lease.phase != LeasePhase.Held)) {
        return stay(lease)
    }
    if (lease.networkId != null && lease.networkId != networkId) return stay(lease)
    return end(lease, LeaseEnd.NetworkLost, forget = false)
}

private fun onAuth(lease: NetworkLease, generation: Long): LeaseTransition {
    if (generation != lease.generation || (lease.phase != LeasePhase.Acquiring && lease.phase != LeasePhase.Held)) {
        return stay(lease)
    }
    return end(lease, LeaseEnd.AuthRejected, forget = true)
}

private fun end(lease: NetworkLease, reason: LeaseEnd, forget: Boolean): LeaseTransition {
    if (lease.phase != LeasePhase.Acquiring && lease.phase != LeasePhase.Held) return stay(lease)
    return LeaseTransition(
        lease.copy(
            phase = LeasePhase.Ended,
            generation = lease.generation + 1,
            networkId = null,
            ended = reason,
            bindsProcess = false,
        ),
        listOf(LeaseEffect.UNREGISTER),
        forgetSsid = if (forget) lease.target?.ssid else null,
    )
}

private fun sameCamera(current: LeaseTarget, next: LeaseTarget): Boolean {
    if (!current.ssid.equals(next.ssid, ignoreCase = true)) return false
    val left = current.bssid
    val right = next.bssid
    return left == null || right == null || left.equals(right, ignoreCase = true)
}

private fun stay(lease: NetworkLease) = LeaseTransition(lease)
