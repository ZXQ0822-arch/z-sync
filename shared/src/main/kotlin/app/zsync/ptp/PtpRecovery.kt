package app.zsync.ptp

val REOPEN_WAITS_MS = listOf(1_000L, 2_000L, 4_000L)

enum class PtpKind {
    Read,
    Delete,
}

enum class PtpBreak {
    CameraClosed,
    TimedOut,
    SessionNotOpen,
}

enum class RecoveryStep {
    Ready,
    RetryingRead,
    WaitingToReopen,
    OpeningSession,
    ReacquiringNetwork,
    NeedsPerson,
    Ended,
}

enum class RecoveryEffect {
    RETRY_READ,
    INSPECT_OBJECT,
    PAUSE_QUEUE,
    CLOSE_SOCKETS,
    OPEN_SESSION,
    RESUME_QUEUE,
    SCAN,
    REACQUIRE_LEASE,
    START_ATTEMPT,
    RELEASE_LEASE,
    CLEAR_QUEUE,
    KEEPALIVE,
}

data class PtpRecovery(
    val step: RecoveryStep = RecoveryStep.Ready,
    val generation: Long = 0,
    val readRetried: Boolean = false,
    val reopenSlot: Int = 0,
    val waitUntil: Long? = null,
    val sessionOpen: Boolean = true,
    val onPhotos: Boolean = true,
    val thumbnailsKept: Boolean = true,
    val offset: Long = 0,
    val queuePaused: Boolean = false,
    val busy: Boolean = false,
    val eventReading: Boolean = true,
    val closeRequested: Boolean = false,
)

sealed interface RecoveryEvent {
    data class Failed(val kind: PtpKind, val reason: PtpBreak, val now: Long, val generation: Long) : RecoveryEvent

    data class Tick(val now: Long) : RecoveryEvent

    data class ReadOk(val generation: Long) : RecoveryEvent

    data class SessionOpened(val generation: Long) : RecoveryEvent

    data class SessionFailed(val generation: Long, val now: Long) : RecoveryEvent

    data class Lost(val generation: Long) : RecoveryEvent

    data class NetworkReady(val generation: Long) : RecoveryEvent

    data object Continue : RecoveryEvent

    data object Cancel : RecoveryEvent

    data object TransactionBegan : RecoveryEvent

    data object TransactionEnded : RecoveryEvent

    data object CloseAsked : RecoveryEvent

    data object Keepalive : RecoveryEvent
}

data class RecoveryTransition(
    val state: PtpRecovery,
    val effects: List<RecoveryEffect> = emptyList(),
    val forgetPassword: Boolean = false,
)

fun recoveryStatus(state: PtpRecovery): String? {
    return when (state.step) {
        RecoveryStep.WaitingToReopen,
        RecoveryStep.OpeningSession,
        RecoveryStep.ReacquiringNetwork,
        RecoveryStep.NeedsPerson,
        -> "正在恢复相机连接"
        else -> null
    }
}

fun recoveryDetail(state: PtpRecovery): String? {
    return when (state.step) {
        RecoveryStep.WaitingToReopen, RecoveryStep.OpeningSession -> "正在同一网络上重建会话"
        RecoveryStep.ReacquiringNetwork -> "正在重新连接相机热点"
        RecoveryStep.NeedsPerson -> "选择继续或取消"
        else -> null
    }
}

fun recoveryChoices(state: PtpRecovery): Pair<String, String>? {
    return if (state.step == RecoveryStep.NeedsPerson) "继续连接" to "取消连接" else null
}

fun reduce(state: PtpRecovery, event: RecoveryEvent): RecoveryTransition {
    return when (event) {
        is RecoveryEvent.Failed -> onFailed(state, event)
        is RecoveryEvent.Tick -> onTick(state, event.now)
        is RecoveryEvent.ReadOk -> if (event.generation != state.generation || state.step != RecoveryStep.RetryingRead) {
            stay(state)
        } else {
            stay(state.copy(step = RecoveryStep.Ready, readRetried = false))
        }
        is RecoveryEvent.SessionOpened -> onSessionOpened(state, event.generation)
        is RecoveryEvent.SessionFailed -> onSessionFailed(state, event.generation, event.now)
        is RecoveryEvent.Lost -> onLost(state, event.generation)
        is RecoveryEvent.NetworkReady -> if (event.generation != state.generation || state.step != RecoveryStep.ReacquiringNetwork) {
            stay(state)
        } else {
            RecoveryTransition(state.copy(step = RecoveryStep.OpeningSession, waitUntil = null), listOf(RecoveryEffect.OPEN_SESSION))
        }
        RecoveryEvent.Continue -> onContinue(state)
        RecoveryEvent.Cancel -> onCancel(state)
        RecoveryEvent.TransactionBegan -> if (state.busy || state.closeRequested) stay(state) else stay(state.copy(busy = true))
        RecoveryEvent.TransactionEnded -> onTransactionEnded(state)
        RecoveryEvent.CloseAsked -> onClose(state)
        RecoveryEvent.Keepalive -> if (state.step == RecoveryStep.Ready && state.sessionOpen && !state.busy && !state.closeRequested) {
            RecoveryTransition(state, listOf(RecoveryEffect.KEEPALIVE))
        } else {
            stay(state)
        }
    }
}

private fun onFailed(state: PtpRecovery, event: RecoveryEvent.Failed): RecoveryTransition {
    if (event.generation != state.generation || state.step == RecoveryStep.WaitingToReopen ||
        state.step == RecoveryStep.OpeningSession || state.step == RecoveryStep.ReacquiringNetwork ||
        state.step == RecoveryStep.NeedsPerson || state.step == RecoveryStep.Ended
    ) {
        return stay(state)
    }
    if (event.kind == PtpKind.Delete && event.reason == PtpBreak.TimedOut && state.sessionOpen) {
        return RecoveryTransition(state, listOf(RecoveryEffect.INSPECT_OBJECT))
    }
    if (event.kind == PtpKind.Read && event.reason != PtpBreak.SessionNotOpen && state.sessionOpen && !state.readRetried) {
        return RecoveryTransition(
            state.copy(step = RecoveryStep.RetryingRead, readRetried = true),
            listOf(RecoveryEffect.RETRY_READ),
        )
    }
    return beginReopen(state, event.now)
}

private fun onTick(state: PtpRecovery, now: Long): RecoveryTransition {
    val due = state.waitUntil ?: return stay(state)
    if (state.step != RecoveryStep.WaitingToReopen || now < due) return stay(state)
    return RecoveryTransition(
        state.copy(step = RecoveryStep.OpeningSession, waitUntil = null),
        listOf(RecoveryEffect.OPEN_SESSION),
    )
}

private fun onSessionOpened(state: PtpRecovery, generation: Long): RecoveryTransition {
    if (generation != state.generation || state.step != RecoveryStep.OpeningSession) return stay(state)
    val opened = state.copy(
        step = RecoveryStep.Ready,
        sessionOpen = true,
        eventReading = true,
        queuePaused = false,
        readRetried = false,
        reopenSlot = 0,
        waitUntil = null,
    )
    return if (state.closeRequested) {
        RecoveryTransition(
            opened.copy(sessionOpen = false, eventReading = false, queuePaused = true),
            listOf(RecoveryEffect.CLOSE_SOCKETS),
        )
    } else {
        RecoveryTransition(opened, listOf(RecoveryEffect.RESUME_QUEUE))
    }
}

private fun onSessionFailed(state: PtpRecovery, generation: Long, now: Long): RecoveryTransition {
    if (generation != state.generation || state.step != RecoveryStep.OpeningSession) return stay(state)
    if (state.reopenSlot >= REOPEN_WAITS_MS.lastIndex) {
        return RecoveryTransition(state.copy(step = RecoveryStep.NeedsPerson, waitUntil = null))
    }
    val next = state.reopenSlot + 1
    return RecoveryTransition(
        state.copy(
            step = RecoveryStep.WaitingToReopen,
            reopenSlot = next,
            waitUntil = now + REOPEN_WAITS_MS[next],
        ),
    )
}

private fun onLost(state: PtpRecovery, generation: Long): RecoveryTransition {
    if (generation != state.generation || state.step == RecoveryStep.Ended || state.step == RecoveryStep.ReacquiringNetwork) {
        return stay(state)
    }
    return RecoveryTransition(
        state.copy(
            step = RecoveryStep.ReacquiringNetwork,
            generation = state.generation + 1,
            sessionOpen = false,
            eventReading = false,
            queuePaused = true,
            waitUntil = null,
            readRetried = false,
        ),
        listOf(RecoveryEffect.PAUSE_QUEUE, RecoveryEffect.SCAN, RecoveryEffect.REACQUIRE_LEASE),
    )
}

private fun onContinue(state: PtpRecovery): RecoveryTransition {
    if (state.step != RecoveryStep.NeedsPerson) return stay(state)
    return RecoveryTransition(
        state.copy(
            step = RecoveryStep.Ready,
            generation = state.generation + 1,
            sessionOpen = false,
            eventReading = false,
            queuePaused = true,
            waitUntil = null,
        ),
        listOf(RecoveryEffect.START_ATTEMPT),
    )
}

private fun onCancel(state: PtpRecovery): RecoveryTransition {
    if (state.step != RecoveryStep.NeedsPerson) return stay(state)
    return RecoveryTransition(
        state.copy(
            step = RecoveryStep.Ended,
            generation = state.generation + 1,
            onPhotos = false,
            sessionOpen = false,
            eventReading = false,
            queuePaused = true,
            waitUntil = null,
        ),
        listOf(RecoveryEffect.RELEASE_LEASE, RecoveryEffect.CLOSE_SOCKETS, RecoveryEffect.CLEAR_QUEUE),
    )
}

private fun onTransactionEnded(state: PtpRecovery): RecoveryTransition {
    val idle = state.copy(busy = false)
    return if (state.closeRequested && state.sessionOpen) {
        RecoveryTransition(
            idle.copy(sessionOpen = false, eventReading = false),
            listOf(RecoveryEffect.CLOSE_SOCKETS),
        )
    } else {
        stay(idle)
    }
}

private fun onClose(state: PtpRecovery): RecoveryTransition {
    if (state.closeRequested || !state.sessionOpen) return stay(state.copy(closeRequested = true))
    if (state.busy) return stay(state.copy(closeRequested = true))
    return RecoveryTransition(
        state.copy(sessionOpen = false, eventReading = false, closeRequested = true),
        listOf(RecoveryEffect.CLOSE_SOCKETS),
    )
}

private fun beginReopen(state: PtpRecovery, now: Long): RecoveryTransition {
    return RecoveryTransition(
        state.copy(
            step = RecoveryStep.WaitingToReopen,
            sessionOpen = false,
            eventReading = false,
            queuePaused = true,
            waitUntil = now + REOPEN_WAITS_MS[0],
            reopenSlot = 0,
            onPhotos = true,
            thumbnailsKept = true,
        ),
        listOf(RecoveryEffect.PAUSE_QUEUE, RecoveryEffect.CLOSE_SOCKETS),
    )
}

private fun stay(state: PtpRecovery) = RecoveryTransition(state)
