package app.zsync.session

import app.zsync.model.isAcceptableHotspotPassword

const val CONNECT_LIMIT_MS = 60_000L

enum class JourneyPage {
    FirstHome,
    FirstConnecting,
    FirstCantFind,
    FirstPasswordError,
    SecondAuto,
    SecondReturn,
    SecondConnecting,
    SecondCantFind,
    SecondPasswordError,
    Photos,
}

enum class JourneyModal {
    None,
    WifiOn,
    CancelLink,
    CancelAuto,
}

enum class JourneyEffect {
    SCAN,
    JOIN,
    RELEASE,
    OPEN_PTP,
    FORGET,
}

data class JourneyState(
    val page: JourneyPage = JourneyPage.FirstHome,
    val modal: JourneyModal = JourneyModal.None,
    val savedSsid: String? = null,
    val hasSavedPassword: Boolean = false,
    val typedPassword: String? = null,
    val seen: List<String> = emptyList(),
    val targetSsid: String? = null,
    val generation: Long = 0,
    val deadlineAt: Long? = null,
    val networkReady: Boolean = false,
    val ptpOpen: Boolean = false,
    val joinSent: Boolean = false,
    val followSaved: Boolean = false,
    val sessionRequested: Boolean = false,
    val attempts: Int = 0,
    val disconnected: Boolean = false,
) {
    override fun toString(): String {
        return "JourneyState(page=$page, modal=$modal, saved=$savedSsid, target=$targetSsid, generation=$generation)"
    }
}

sealed interface JourneyEvent {
    data class Opened(val now: Long) : JourneyEvent

    data class Scanned(val names: List<String>, val generation: Long) : JourneyEvent

    data class PasswordChanged(val password: String) : JourneyEvent

    data class PickHotspot(val ssid: String) : JourneyEvent

    data class ConnectCamera(val now: Long) : JourneyEvent

    data object ContinueConnect : JourneyEvent

    data class ReconnectLast(val now: Long) : JourneyEvent

    data class ConnectAgain(val now: Long) : JourneyEvent

    data object CancelConnect : JourneyEvent

    data object CancelAuto : JourneyEvent

    data class Confirm(val now: Long) : JourneyEvent

    data object Dismiss : JourneyEvent

    data class Tick(val now: Long) : JourneyEvent

    data class AuthRejected(val generation: Long) : JourneyEvent

    data class HotspotAbsent(val generation: Long) : JourneyEvent

    data class NetworkAcquired(val generation: Long) : JourneyEvent

    data class PtpSessionOpened(val generation: Long) : JourneyEvent

    data class PtpSessionFailed(val generation: Long) : JourneyEvent

    data class NetworkLost(val generation: Long, val now: Long) : JourneyEvent

    /** The media session died while the camera network may still be up. */
    data class SessionLost(val generation: Long, val now: Long) : JourneyEvent

    /** The system refused the join or the person backed out of the system Wi-Fi page. */
    data class JoinFailed(val generation: Long) : JourneyEvent
}

data class JourneyTransition(
    val state: JourneyState,
    val effects: List<JourneyEffect> = emptyList(),
)

fun initialJourney(savedSsid: String?, hasSavedPassword: Boolean): JourneyState {
    return JourneyState(savedSsid = cleanSsid(savedSsid), hasSavedPassword = hasSavedPassword)
}

fun joins(page: JourneyPage): Boolean {
    return page == JourneyPage.FirstConnecting ||
        page == JourneyPage.SecondAuto ||
        page == JourneyPage.SecondConnecting
}

fun nikonHotspots(names: List<String>): List<String> {
    val kept = LinkedHashMap<String, String>()
    names.forEach { raw ->
        val name = raw.trim().removeSurrounding("\"")
        if (name.isEmpty() || !name.startsWith("NIKON", ignoreCase = true)) return@forEach
        val key = name.lowercase()
        if (!kept.containsKey(key)) kept[key] = name
    }
    return kept.values.toList()
}

fun reduce(state: JourneyState, event: JourneyEvent): JourneyTransition {
    return when (event) {
        is JourneyEvent.Opened -> onOpened(state, event.now)
        is JourneyEvent.Scanned -> if (stale(state, event.generation)) stay(state) else onScan(state, event.names)
        is JourneyEvent.PasswordChanged -> stay(state.copy(typedPassword = event.password))
        is JourneyEvent.PickHotspot -> onPick(state, event.ssid)
        is JourneyEvent.ConnectCamera -> onConnectCamera(state, event.now)
        JourneyEvent.ContinueConnect -> onContinue(state)
        is JourneyEvent.ReconnectLast -> onReconnectLast(state, event.now)
        is JourneyEvent.ConnectAgain -> onConnectAgain(state, event.now)
        JourneyEvent.CancelConnect -> onCancelConnect(state)
        JourneyEvent.CancelAuto -> onCancelAuto(state)
        is JourneyEvent.Confirm -> onConfirm(state, event.now)
        JourneyEvent.Dismiss -> if (state.modal == JourneyModal.None) stay(state) else stay(state.copy(modal = JourneyModal.None))
        is JourneyEvent.Tick -> onTick(state, event.now)
        is JourneyEvent.AuthRejected -> if (stale(state, event.generation)) stay(state) else onAuth(state)
        is JourneyEvent.HotspotAbsent -> stayIfJoining(state, event.generation)
        is JourneyEvent.NetworkAcquired -> if (stale(state, event.generation)) stay(state) else onNetwork(state)
        is JourneyEvent.PtpSessionOpened -> if (stale(state, event.generation)) stay(state) else onPtp(state)
        is JourneyEvent.PtpSessionFailed -> stayIfJoining(state, event.generation)
        is JourneyEvent.NetworkLost -> if (stale(state, event.generation)) stay(state) else onNetworkLost(state, event.now)
        is JourneyEvent.SessionLost -> if (stale(state, event.generation)) stay(state) else onSessionLost(state, event.now)
        is JourneyEvent.JoinFailed -> if (stale(state, event.generation)) stay(state) else onJoinFailed(state)
    }
}

private fun onOpened(state: JourneyState, now: Long): JourneyTransition {
    val cleared = state.copy(
        modal = JourneyModal.None,
        networkReady = false,
        ptpOpen = false,
        joinSent = false,
        sessionRequested = false,
        deadlineAt = null,
        targetSsid = null,
        disconnected = false,
    )
    val saved = cleared.hasSavedPassword && !cleared.savedSsid.isNullOrBlank()
    return if (saved) {
        startAttempt(cleared.copy(followSaved = true), JourneyPage.SecondAuto, now)
    } else {
        JourneyTransition(
            cleared.copy(
                page = JourneyPage.FirstHome,
                followSaved = false,
                generation = cleared.generation + 1,
            ),
            listOf(JourneyEffect.SCAN),
        )
    }
}

private fun onScan(state: JourneyState, names: List<String>): JourneyTransition {
    if (!searches(state.page)) return stay(state)
    val seen = nikonHotspots(names)
    val ticking = if (joins(state.page)) state.copy(attempts = state.attempts + 1) else state
    if (!joins(ticking.page) || ticking.joinSent) {
        return JourneyTransition(ticking.copy(seen = seen), listOf(JourneyEffect.SCAN))
    }
    val desired = if (ticking.followSaved) ticking.savedSsid else ticking.targetSsid ?: seen.firstOrNull()
    val visible = desired != null && seen.any { it.equals(desired, ignoreCase = true) }
    return if (visible) {
        JourneyTransition(
            ticking.copy(seen = seen, targetSsid = desired, joinSent = true),
            listOf(JourneyEffect.JOIN, JourneyEffect.SCAN),
        )
    } else {
        JourneyTransition(ticking.copy(seen = seen), listOf(JourneyEffect.SCAN))
    }
}

private fun onPick(state: JourneyState, ssid: String): JourneyTransition {
    if (!listsHotspots(state.page)) return stay(state)
    val match = state.seen.firstOrNull { it.equals(ssid, ignoreCase = true) } ?: return stay(state)
    return stay(state.copy(targetSsid = match))
}

/**
 * Whether a scan result should drive the link machine. While the app lists hotspots the person is
 * choosing a camera, so a scan only refreshes that list; driving the machine there makes it flip to
 * Searching and emit a join the effect runner then has to ignore.
 */
fun scanDrivesTheLink(page: JourneyPage): Boolean = !listsHotspots(page)

fun listsHotspots(page: JourneyPage): Boolean {
    return page == JourneyPage.FirstHome ||
        page == JourneyPage.FirstCantFind ||
        page == JourneyPage.SecondReturn ||
        page == JourneyPage.SecondCantFind
}

private fun onConnectCamera(state: JourneyState, now: Long): JourneyTransition {
    if (!isAcceptableHotspotPassword(state.typedPassword.orEmpty())) return stay(state)
    return when (state.page) {
        JourneyPage.FirstHome -> if (state.modal == JourneyModal.None) {
            stay(state.copy(modal = JourneyModal.WifiOn))
        } else {
            stay(state)
        }
        JourneyPage.SecondReturn -> startAttempt(state.copy(followSaved = false), JourneyPage.SecondConnecting, now)
        else -> stay(state)
    }
}

private fun onContinue(state: JourneyState): JourneyTransition {
    val miss = state.page == JourneyPage.FirstCantFind || state.page == JourneyPage.SecondCantFind
    if (!miss || state.modal != JourneyModal.None) return stay(state)
    return stay(state.copy(modal = JourneyModal.WifiOn))
}

private fun onReconnectLast(state: JourneyState, now: Long): JourneyTransition {
    if (state.page != JourneyPage.SecondReturn || !state.hasSavedPassword || state.savedSsid.isNullOrBlank()) {
        return stay(state)
    }
    return startAttempt(state.copy(followSaved = true), JourneyPage.SecondConnecting, now)
}

private fun onConnectAgain(state: JourneyState, now: Long): JourneyTransition {
    val passwordPage = state.page == JourneyPage.FirstPasswordError || state.page == JourneyPage.SecondPasswordError
    if (!passwordPage || !isAcceptableHotspotPassword(state.typedPassword.orEmpty())) return stay(state)
    val page = if (state.page == JourneyPage.FirstPasswordError) JourneyPage.FirstConnecting else JourneyPage.SecondConnecting
    return startAttempt(state, page, now)
}

private fun onCancelConnect(state: JourneyState): JourneyTransition {
    val connecting = state.page == JourneyPage.FirstConnecting || state.page == JourneyPage.SecondConnecting
    if (!connecting || state.modal != JourneyModal.None) return stay(state)
    return stay(state.copy(modal = JourneyModal.CancelLink))
}

private fun onCancelAuto(state: JourneyState): JourneyTransition {
    if (state.page != JourneyPage.SecondAuto || state.modal != JourneyModal.None) return stay(state)
    return stay(state.copy(modal = JourneyModal.CancelAuto))
}

private fun onConfirm(state: JourneyState, now: Long): JourneyTransition {
    return when (state.modal) {
        JourneyModal.None -> stay(state)
        JourneyModal.WifiOn -> when (state.page) {
            JourneyPage.FirstHome, JourneyPage.FirstCantFind ->
                startAttempt(state, JourneyPage.FirstConnecting, now)
            JourneyPage.SecondCantFind ->
                startAttempt(state, JourneyPage.SecondConnecting, now)
            else -> stay(state)
        }
        JourneyModal.CancelLink -> when (state.page) {
            JourneyPage.FirstConnecting -> stop(state, JourneyPage.FirstHome)
            JourneyPage.SecondConnecting -> stop(state, JourneyPage.SecondReturn)
            else -> stay(state)
        }
        JourneyModal.CancelAuto -> if (state.page == JourneyPage.SecondAuto) {
            stop(state, JourneyPage.SecondReturn)
        } else {
            stay(state)
        }
    }
}

private fun onTick(state: JourneyState, now: Long): JourneyTransition {
    val deadline = state.deadlineAt ?: return stay(state)
    if (now < deadline || !joins(state.page)) return stay(state)
    val page = when (state.page) {
        JourneyPage.FirstConnecting -> JourneyPage.FirstCantFind
        JourneyPage.SecondAuto, JourneyPage.SecondConnecting -> JourneyPage.SecondCantFind
        else -> return stay(state)
    }
    return JourneyTransition(
        state.copy(
            page = page,
            modal = JourneyModal.None,
            generation = state.generation + 1,
            deadlineAt = null,
            joinSent = false,
            networkReady = false,
            sessionRequested = false,
            targetSsid = null,
        ),
        listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN),
    )
}

private fun onAuth(state: JourneyState): JourneyTransition {
    val page = when (state.page) {
        JourneyPage.FirstConnecting -> JourneyPage.FirstPasswordError
        JourneyPage.SecondAuto, JourneyPage.SecondConnecting -> JourneyPage.SecondPasswordError
        else -> return stay(state)
    }
    return JourneyTransition(
        state.copy(
            page = page,
            modal = JourneyModal.None,
            hasSavedPassword = false,
            typedPassword = null,
            generation = state.generation + 1,
            deadlineAt = null,
            joinSent = false,
            networkReady = false,
            sessionRequested = false,
            savedSsid = state.savedSsid ?: state.targetSsid,
        ),
        listOf(JourneyEffect.RELEASE, JourneyEffect.FORGET),
    )
}

private fun onNetwork(state: JourneyState): JourneyTransition {
    if (!joins(state.page) || state.sessionRequested) return stay(state)
    return JourneyTransition(
        state.copy(networkReady = true, sessionRequested = true),
        listOf(JourneyEffect.OPEN_PTP),
    )
}

private fun onPtp(state: JourneyState): JourneyTransition {
    if (!joins(state.page) || !state.networkReady) return stay(state)
    val saved = state.targetSsid ?: state.savedSsid
    return JourneyTransition(
        state.copy(
            page = JourneyPage.Photos,
            modal = JourneyModal.None,
            ptpOpen = true,
            hasSavedPassword = true,
            savedSsid = saved,
            typedPassword = null,
            deadlineAt = null,
            joinSent = false,
            generation = state.generation + 1,
            disconnected = false,
        ),
    )
}

private fun startAttempt(state: JourneyState, page: JourneyPage, now: Long): JourneyTransition {
    val target = if (state.followSaved) state.savedSsid else state.targetSsid ?: state.seen.firstOrNull()
    val visible = target != null && state.seen.any { it.equals(target, ignoreCase = true) }
    return JourneyTransition(
        state.copy(
            page = page,
            modal = JourneyModal.None,
            generation = state.generation + 1,
            deadlineAt = now + CONNECT_LIMIT_MS,
            targetSsid = target,
            networkReady = false,
            ptpOpen = false,
            joinSent = visible,
            sessionRequested = false,
            attempts = 0,
            disconnected = false,
        ),
        listOfNotNull(if (visible) JourneyEffect.JOIN else null, JourneyEffect.SCAN),
    )
}

private fun stop(state: JourneyState, page: JourneyPage): JourneyTransition {
    return JourneyTransition(
        state.copy(
            page = page,
            modal = JourneyModal.None,
            generation = state.generation + 1,
            deadlineAt = null,
            joinSent = false,
            networkReady = false,
            sessionRequested = false,
            targetSsid = null,
            disconnected = false,
        ),
        listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN),
    )
}

private fun searches(page: JourneyPage): Boolean {
    return page == JourneyPage.FirstHome ||
        page == JourneyPage.FirstCantFind ||
        page == JourneyPage.SecondReturn ||
        page == JourneyPage.SecondCantFind ||
        joins(page)
}

private fun stayIfJoining(state: JourneyState, generation: Long): JourneyTransition {
    if (stale(state, generation) || !joins(state.page)) return stay(state)
    return stay(state)
}

private fun onNetworkLost(state: JourneyState, now: Long): JourneyTransition {
    if (joins(state.page)) {
        val page = when (state.page) {
            JourneyPage.FirstConnecting -> JourneyPage.FirstCantFind
            JourneyPage.SecondAuto, JourneyPage.SecondConnecting -> JourneyPage.SecondCantFind
            else -> return stay(state)
        }
        return JourneyTransition(
            state.copy(
                page = page,
                modal = JourneyModal.None,
                generation = state.generation + 1,
                deadlineAt = null,
                joinSent = false,
                networkReady = false,
                sessionRequested = false,
                targetSsid = null,
            ),
            listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN),
        )
    }
    if (state.page == JourneyPage.Photos) return reconnectAfterLoss(state, now)
    return stay(state)
}

private fun onSessionLost(state: JourneyState, now: Long): JourneyTransition {
    if (state.page != JourneyPage.Photos) return stay(state)
    return reconnectAfterLoss(state, now)
}

/** Photos page lost the camera: go back to the auto connect page and say so. */
private fun reconnectAfterLoss(state: JourneyState, now: Long): JourneyTransition {
    if (state.hasSavedPassword && !state.savedSsid.isNullOrBlank()) {
        val attempt = startAttempt(
            state.copy(
                followSaved = true,
                networkReady = false,
                ptpOpen = false,
                sessionRequested = false,
                joinSent = false,
            ),
            JourneyPage.SecondAuto,
            now,
        )
        return attempt.copy(state = attempt.state.copy(disconnected = true))
    }
    return JourneyTransition(
        state.copy(
            page = JourneyPage.FirstHome,
            modal = JourneyModal.None,
            generation = state.generation + 1,
            deadlineAt = null,
            joinSent = false,
            networkReady = false,
            ptpOpen = false,
            sessionRequested = false,
            targetSsid = null,
            disconnected = true,
        ),
        listOf(JourneyEffect.SCAN),
    )
}

private fun onJoinFailed(state: JourneyState): JourneyTransition {
    val home = when (state.page) {
        JourneyPage.FirstConnecting -> JourneyPage.FirstHome
        JourneyPage.SecondAuto, JourneyPage.SecondConnecting -> JourneyPage.SecondReturn
        else -> return stay(state)
    }
    return stop(state, home)
}

private fun stale(state: JourneyState, generation: Long) = generation != state.generation

private fun stay(state: JourneyState) = JourneyTransition(state)

private fun cleanSsid(value: String?): String? {
    val name = value?.trim()?.removeSurrounding("\"")
    return name?.takeIf { it.isNotEmpty() }
}
