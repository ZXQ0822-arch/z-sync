package app.zsync.session

enum class SessionPhase {
    Closed,
    Opening,
    Open,
}

data class ConnectionState(
    val journey: JourneyState,
    val lease: NetworkLease = NetworkLease(),
    val session: SessionPhase = SessionPhase.Closed,
) {
    fun connected(): Boolean {
        return journey.page == JourneyPage.Photos &&
            journey.ptpOpen &&
            lease.phase == LeasePhase.Held &&
            session == SessionPhase.Open
    }
}

sealed interface ConnectionEvent {
    data class User(val event: JourneyEvent) : ConnectionEvent

    data class Network(val event: LeaseEvent) : ConnectionEvent
}

data class ConnectionTransition(
    val state: ConnectionState,
    val journeyEffects: List<JourneyEffect> = emptyList(),
    val leaseEffects: List<LeaseEffect> = emptyList(),
)

fun initialConnection(savedSsid: String?, hasSavedPassword: Boolean): ConnectionState {
    return ConnectionState(journey = initialJourney(savedSsid, hasSavedPassword))
}

fun ConnectionState.withOpenSession(savedSsid: String?, hasSavedPassword: Boolean): ConnectionState {
    val cleaned = savedSsid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    return copy(
        journey = journey.copy(
            page = JourneyPage.Photos,
            ptpOpen = true,
            networkReady = true,
            hasSavedPassword = hasSavedPassword,
            savedSsid = cleaned ?: journey.savedSsid,
            modal = JourneyModal.None,
            joinSent = false,
            deadlineAt = null,
        ),
        lease = when (lease.phase) {
            LeasePhase.Acquiring, LeasePhase.Held -> lease.copy(phase = LeasePhase.Held)
            else -> lease
        },
        session = SessionPhase.Open,
    )
}

fun reduce(state: ConnectionState, event: ConnectionEvent): ConnectionTransition {
    return when (event) {
        is ConnectionEvent.User -> onUser(state, event.event)
        is ConnectionEvent.Network -> onNetwork(state, event.event)
    }
}

private fun onUser(state: ConnectionState, event: JourneyEvent): ConnectionTransition {
    val beforeModal = state.journey.modal
    val journey = reduce(state.journey, event)
    var lease = state.lease
    var session = state.session
    val leaseEffects = mutableListOf<LeaseEffect>()

    // Android reported the camera Wi-Fi gone. End the held lease before applying the
    // journey's effects, so a re-acquire (JOIN) from onNetworkLost reopens a fresh lease
    // instead of being immediately ended by this same event.
    if (event is JourneyEvent.NetworkLost) {
        applyLease(reduce(lease, LeaseEvent.Lost(lease.generation, lease.networkId ?: -1L))).also {
            lease = it.lease
            leaseEffects += it.effects
        }
        session = SessionPhase.Closed
    }

    journey.effects.forEach { effect ->
        when (effect) {
            JourneyEffect.JOIN -> {
                val ssid = journey.state.targetSsid ?: journey.state.savedSsid ?: return@forEach
                applyLease(reduce(lease, LeaseEvent.Acquire(ssid, null))).also {
                    lease = it.lease
                    leaseEffects += it.effects
                }
            }
            JourneyEffect.RELEASE -> {
                val stop = if (event is JourneyEvent.Confirm &&
                    (beforeModal == JourneyModal.CancelLink || beforeModal == JourneyModal.CancelAuto)
                ) {
                    LeaseEvent.UserCancel
                } else {
                    LeaseEvent.AttemptStopped
                }
                applyLease(reduce(lease, stop)).also {
                    lease = it.lease
                    leaseEffects += it.effects
                }
            }
            JourneyEffect.OPEN_PTP -> session = SessionPhase.Opening
            JourneyEffect.FORGET -> Unit
            JourneyEffect.SCAN -> Unit
        }
    }

    when (event) {
        is JourneyEvent.AuthRejected -> {
            applyLease(reduce(lease, LeaseEvent.AuthRejected(event.generation))).also {
                lease = it.lease
                leaseEffects += it.effects
            }
            session = SessionPhase.Closed
        }
        is JourneyEvent.PtpSessionOpened -> if (journey.state.ptpOpen) {
            session = SessionPhase.Open
        }
        is JourneyEvent.PtpSessionFailed -> if (event.generation == state.journey.generation) {
            session = SessionPhase.Closed
        }
        is JourneyEvent.SessionLost -> session = SessionPhase.Closed
        else -> Unit
    }

    if (JourneyEffect.RELEASE in journey.effects) {
        session = SessionPhase.Closed
    }

    return ConnectionTransition(
        ConnectionState(journey.state, lease, session),
        journey.effects,
        leaseEffects,
    )
}

private fun onNetwork(state: ConnectionState, event: LeaseEvent): ConnectionTransition {
    val lease = reduce(state.lease, event)
    return ConnectionTransition(state.copy(lease = lease.lease), leaseEffects = lease.effects)
}

private fun applyLease(transition: LeaseTransition): LeaseTransition = transition
