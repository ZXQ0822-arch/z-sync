package app.zsync.session

import app.zsync.model.isAcceptableHotspotPassword
import app.zsync.model.isCameraSsid

data class LinkMemory(
    val savedSsid: String? = null,
    val hasSavedPassword: Boolean = false,
)

data class LinkState(
    val phase: LinkPhase = LinkPhase.NeedPassword,
    val memory: LinkMemory = LinkMemory(),
    val targetSsid: String? = null,
    val pendingPassword: String? = null,
    val probing: Boolean = false,
    val reconnects: Int = 0,
) {
    override fun toString(): String {
        return "LinkState(phase=$phase, saved=${memory.hasSavedPassword}, target=$targetSsid)"
    }
}

enum class LinkEffect {
    SCAN,
    JOIN,
    CHECK_INTERNET,
    SAVE_PASSWORD,
    FORGET_PASSWORD,
}

sealed interface LinkEvent {
    data object ScreenOpened : LinkEvent

    data class ScanResult(val names: List<String>) : LinkEvent

    data class PasswordSubmitted(val password: String) : LinkEvent

    data class TargetChosen(val ssid: String) : LinkEvent

    data object ConnectTapped : LinkEvent

    data object SystemPromptVisible : LinkEvent

    data object Joined : LinkEvent

    data class Internet(val available: Boolean) : LinkEvent

    data class JoinUnavailable(val reason: JoinRejection) : LinkEvent

    data class JoinError(val detail: String) : LinkEvent

    data object NetworkLost : LinkEvent

    data object CameraMissed : LinkEvent

    data object CameraBusy : LinkEvent

    data object Disconnect : LinkEvent

    data object AttemptExpired : LinkEvent
}

data class LinkTransition(
    val state: LinkState,
    val effects: List<LinkEffect> = emptyList(),
)

fun initialLinkState(savedSsid: String?, savedPassword: String?): LinkState {
    val saved = !savedPassword.isNullOrEmpty() && isAcceptableHotspotPassword(savedPassword)
    return LinkState(
        phase = if (saved) LinkPhase.Ready else LinkPhase.NeedPassword,
        memory = LinkMemory(savedSsid = savedSsid, hasSavedPassword = saved),
    )
}

fun chooseSsid(names: List<String>, savedName: String?): String? {
    val cleaned = names.map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
    if (!savedName.isNullOrBlank()) {
        cleaned.firstOrNull { it.equals(savedName.trim(), ignoreCase = true) }?.let { return it }
    }
    return cleaned.firstOrNull { isCameraSsid(it) }
}

fun reduce(state: LinkState, event: LinkEvent): LinkTransition {
    return when (event) {
        LinkEvent.ScreenOpened -> onOpened(state)
        is LinkEvent.ScanResult -> if (state.probing) onProbe(state, event.names) else onScan(state, event.names)
        is LinkEvent.PasswordSubmitted -> onPassword(state, event.password)
        is LinkEvent.TargetChosen -> onTarget(state, event.ssid)
        LinkEvent.ConnectTapped -> onConnect(state)
        LinkEvent.SystemPromptVisible -> onSystemPrompt(state)
        LinkEvent.Joined -> onJoined(state)
        is LinkEvent.Internet -> onInternet(state, event.available)
        is LinkEvent.JoinUnavailable -> onUnavailable(state, event.reason)
        LinkEvent.NetworkLost -> onNetworkLost(state)
        LinkEvent.CameraMissed -> onMissed(state)
        is LinkEvent.JoinError -> LinkTransition(
            state.copy(phase = LinkPhase.Failed(LinkFailure.Unknown, event.detail)),
        )
        LinkEvent.CameraBusy -> LinkTransition(state.copy(phase = LinkPhase.Failed(LinkFailure.ClientBusy)))
        LinkEvent.Disconnect -> onDisconnect(state)
        LinkEvent.AttemptExpired -> onAttemptExpired(state)
    }
}

private fun onOpened(state: LinkState): LinkTransition {
    val idle = state.phase is LinkPhase.Ready || state.phase is LinkPhase.NeedPassword
    if (!idle || state.probing || !state.memory.hasSavedPassword) return LinkTransition(state)
    return LinkTransition(state.copy(phase = LinkPhase.Searching(0)), listOf(LinkEffect.SCAN))
}

private fun onProbe(state: LinkState, names: List<String>): LinkTransition {
    val done = state.copy(probing = false)
    val idle = state.phase is LinkPhase.Ready || state.phase is LinkPhase.NeedPassword
    val ssid = chooseSsid(names, state.memory.savedSsid)
    if (!idle || ssid == null || !state.memory.hasSavedPassword) return LinkTransition(done)
    return LinkTransition(done.copy(phase = LinkPhase.Connecting, targetSsid = ssid), listOf(LinkEffect.JOIN))
}

private fun onScan(state: LinkState, names: List<String>): LinkTransition {
    if (state.phase is LinkPhase.Connected ||
        state.phase is LinkPhase.Connecting ||
        state.phase is LinkPhase.AwaitingSystemAllow
    ) {
        return LinkTransition(state)
    }
    val ssid = chosenOrScanned(state, names)
    if (keepsLookingWithoutJoining(state.phase)) {
        if (ssid != null && !canJoin(state)) {
            return LinkTransition(state.copy(phase = LinkPhase.NeedPassword, targetSsid = ssid))
        }
        return LinkTransition(state.copy(targetSsid = ssid), listOf(LinkEffect.SCAN))
    }
    if (ssid == null) {
        if (state.memory.hasSavedPassword || state.phase is LinkPhase.Searching) {
            val scans = ((state.phase as? LinkPhase.Searching)?.scans ?: 0) + 1
            return LinkTransition(
                state.copy(phase = LinkPhase.Searching(scans), targetSsid = null, probing = false),
                listOf(LinkEffect.SCAN),
            )
        }
        return LinkTransition(
            state.copy(phase = LinkPhase.WaitingForHotspot, targetSsid = null),
            listOf(LinkEffect.SCAN),
        )
    }
    val found = state.copy(targetSsid = ssid)
    return if (canJoin(found)) {
        val scans = (state.phase as? LinkPhase.Searching)?.scans ?: 0
        LinkTransition(found.copy(phase = LinkPhase.Searching(scans)), listOf(LinkEffect.JOIN))
    } else {
        LinkTransition(found.copy(phase = LinkPhase.NeedPassword))
    }
}

private fun onDisconnect(state: LinkState): LinkTransition {
    val typedOk = isAcceptableHotspotPassword(state.pendingPassword.orEmpty())
    val memory = if (typedOk) state.memory.copy(hasSavedPassword = true) else state.memory
    return LinkTransition(
        state.copy(
            phase = if (memory.hasSavedPassword) LinkPhase.Ready else LinkPhase.NeedPassword,
            memory = memory,
            targetSsid = null,
            pendingPassword = null,
            reconnects = 0,
        ),
        if (typedOk) listOf(LinkEffect.SAVE_PASSWORD) else emptyList(),
    )
}

private fun onTarget(state: LinkState, ssid: String): LinkTransition {
    val name = ssid.trim().removeSurrounding("\"")
    if (!isCameraSsid(name)) return LinkTransition(state)
    return LinkTransition(state.copy(targetSsid = name))
}

private fun chosenOrScanned(state: LinkState, names: List<String>): String? {
    val picked = state.targetSsid?.trim()?.removeSurrounding("\"")
    if (!picked.isNullOrBlank() && names.any { it.trim().removeSurrounding("\"").equals(picked, ignoreCase = true) }) {
        return picked
    }
    return chooseSsid(names, state.memory.savedSsid)
}

private fun onPassword(state: LinkState, password: String): LinkTransition {
    if (!isAcceptableHotspotPassword(password)) {
        return LinkTransition(state.copy(phase = LinkPhase.NeedPassword, pendingPassword = null))
    }
    val typed = state.copy(
        pendingPassword = password,
        phase = LinkPhase.Connecting,
        reconnects = 0,
        memory = state.memory.copy(hasSavedPassword = true),
    )
    return LinkTransition(typed, listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.JOIN))
}

private fun onConnect(state: LinkState): LinkTransition {
    val failed = state.phase as? LinkPhase.Failed
    if (failed?.failure == LinkFailure.BadPassword) {
        return LinkTransition(state.copy(phase = LinkPhase.NeedPassword, pendingPassword = null))
    }
    if (failed?.failure == LinkFailure.Dropped || failed?.failure == LinkFailure.OffCamera) {
        return LinkTransition(state.copy(phase = LinkPhase.Searching(0), reconnects = 0), listOf(LinkEffect.SCAN))
    }
    if (state.phase is LinkPhase.Connected) return LinkTransition(state)
    if (state.phase is LinkPhase.AwaitingSystemAllow || state.phase is LinkPhase.Connecting) {
        if (!state.memory.hasSavedPassword && !isAcceptableHotspotPassword(state.pendingPassword.orEmpty())) {
            return LinkTransition(state.copy(phase = LinkPhase.NeedPassword, pendingPassword = null, reconnects = 0))
        }
        return LinkTransition(state)
    }
    if (isAcceptableHotspotPassword(state.pendingPassword.orEmpty())) {
        return LinkTransition(state.copy(phase = LinkPhase.Connecting, reconnects = 0), listOf(LinkEffect.JOIN))
    }
    if (!state.memory.hasSavedPassword) {
        return LinkTransition(state.copy(phase = LinkPhase.NeedPassword, pendingPassword = null, reconnects = 0))
    }
    val scans = (state.phase as? LinkPhase.Searching)?.scans ?: 0
    return LinkTransition(state.copy(phase = LinkPhase.Searching(scans)), listOf(LinkEffect.SCAN))
}

private fun onMissed(state: LinkState): LinkTransition {
    val scans = ((state.phase as? LinkPhase.Searching)?.scans ?: 0) + 1
    return LinkTransition(
        state.copy(phase = LinkPhase.Searching(scans), targetSsid = null),
        listOf(LinkEffect.SCAN),
    )
}

private const val MAX_RECONNECTS = 3

private fun onNetworkLost(state: LinkState): LinkTransition {
    val joined = state.phase is LinkPhase.Connected ||
        state.phase is LinkPhase.Connecting ||
        state.phase is LinkPhase.AwaitingSystemAllow
    val looking = joined || state.phase is LinkPhase.Searching
    if (!looking) return LinkTransition(state)
    if (joined && state.reconnects >= MAX_RECONNECTS) {
        return LinkTransition(state.copy(phase = LinkPhase.Failed(LinkFailure.Dropped), reconnects = 0))
    }
    val reconnects = if (joined) state.reconnects + 1 else state.reconnects
    return LinkTransition(
        state.copy(phase = LinkPhase.Searching(0), reconnects = reconnects),
        listOf(LinkEffect.SCAN),
    )
}

private fun onJoined(state: LinkState): LinkTransition {
    if (!attemptStillOpen(state.phase)) return LinkTransition(state)
    return LinkTransition(
        state.copy(
            phase = LinkPhase.Connecting,
            pendingPassword = null,
            memory = state.memory.copy(
                savedSsid = state.targetSsid ?: state.memory.savedSsid,
                hasSavedPassword = true,
            ),
        ),
        listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.CHECK_INTERNET),
    )
}

private fun onInternet(state: LinkState, available: Boolean): LinkTransition {
    if (state.phase !is LinkPhase.Connecting) return LinkTransition(state)
    val ssid = state.targetSsid ?: state.memory.savedSsid ?: CAMERA_LABEL
    return LinkTransition(state.copy(phase = LinkPhase.Connected(ssid, available), reconnects = 0))
}

private const val CAMERA_LABEL = "Z 30"

private fun onUnavailable(state: LinkState, reason: JoinRejection): LinkTransition {
    val joining = state.phase is LinkPhase.Connecting || state.phase is LinkPhase.AwaitingSystemAllow
    if (!joining) return LinkTransition(state)
    return when (reason) {
        JoinRejection.WrongPassword -> LinkTransition(
            state.copy(
                phase = LinkPhase.Failed(LinkFailure.BadPassword),
                memory = state.memory.copy(hasSavedPassword = false),
                pendingPassword = null,
            ),
            listOf(LinkEffect.FORGET_PASSWORD),
        )
        JoinRejection.HotspotMissing -> LinkTransition(
            state.copy(phase = LinkPhase.Failed(LinkFailure.HotspotOff), targetSsid = null),
            listOf(LinkEffect.SCAN),
        )
        JoinRejection.OffCamera -> LinkTransition(
            state.copy(phase = LinkPhase.Failed(LinkFailure.OffCamera)),
        )
        JoinRejection.TimedOut -> LinkTransition(
            state.copy(phase = LinkPhase.Failed(LinkFailure.Dropped)),
            listOf(LinkEffect.SCAN),
        )
    }
}

private fun attemptStillOpen(phase: LinkPhase): Boolean {
    return phase is LinkPhase.Searching ||
        phase is LinkPhase.Connecting ||
        phase is LinkPhase.AwaitingSystemAllow
}

private fun onSystemPrompt(state: LinkState): LinkTransition {
    if (!attemptStillOpen(state.phase)) return LinkTransition(state)
    return LinkTransition(state.copy(phase = LinkPhase.AwaitingSystemAllow))
}

private fun onAttemptExpired(state: LinkState): LinkTransition {
    if (!attemptStillOpen(state.phase)) return LinkTransition(state)
    return LinkTransition(
        state.copy(phase = LinkPhase.Failed(LinkFailure.Dropped)),
        listOf(LinkEffect.SCAN),
    )
}

private fun keepsLookingWithoutJoining(phase: LinkPhase): Boolean {
    if (phase is LinkPhase.WaitingForHotspot) return true
    val failed = phase as? LinkPhase.Failed ?: return false
    return failed.failure == LinkFailure.HotspotOff || failed.failure == LinkFailure.Dropped
}

private fun canJoin(state: LinkState): Boolean {
    return state.memory.hasSavedPassword || isAcceptableHotspotPassword(state.pendingPassword.orEmpty())
}
