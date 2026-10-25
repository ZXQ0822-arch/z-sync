package app.zsync.session

import app.zsync.model.isAcceptableHotspotPassword

data class JourneyDialog(
    val title: String,
    val left: String = "取消",
    val right: String = "确认",
    val centered: Boolean = true,
)

data class JourneyChrome(
    val page: JourneyPage,
    val eyebrow: String?,
    val title: String,
    val titleSize: Int,
    val lines: List<String>,
    val callout: String?,
    val primary: String?,
    val primaryEnabled: Boolean,
    val secondary: String?,
    val placeholder: String?,
    val steps: List<String>,
    val stepsBoxed: Boolean,
    val foundHeading: String?,
    val savedHeading: String?,
    val savedName: String?,
    val savedAction: String?,
    val savedActionEnabled: Boolean,
    val hotspots: List<String>,
    val lastCamera: String?,
    val autoLine: String?,
    val rows: List<String>,
    val attempts: Int,
    val disconnected: Boolean,
    val spinner: Boolean,
    val dialog: JourneyDialog?,
)

/**
 * Every page title is 28, section and status titles are 22.
 *
 * There used to be three sizes here (34 / 22 / 20). The 34 made the connect page title jump against
 * the 28 of the photo page, and 20 was not on the type scale at all. Decided 2026-10-25; see
 * `docs/design/pages/frame-spacing-proposal.html`.
 */
fun titleSizeSp(page: JourneyPage): Int = when (page) {
    JourneyPage.FirstConnecting, JourneyPage.SecondConnecting,
    JourneyPage.FirstCantFind, JourneyPage.SecondCantFind,
    JourneyPage.FirstPasswordError, JourneyPage.SecondPasswordError,
    JourneyPage.SecondReturn,
    -> 22
    else -> 28
}

fun journeyChrome(state: JourneyState): JourneyChrome {
    val copy = journeyCopy(state.page)
    val typedOk = isAcceptableHotspotPassword(state.typedPassword.orEmpty())
    val asksForPassword = state.page == JourneyPage.FirstHome ||
        state.page == JourneyPage.SecondReturn ||
        state.page == JourneyPage.FirstPasswordError ||
        state.page == JourneyPage.SecondPasswordError
    return JourneyChrome(
        page = state.page,
        eyebrow = if (state.page == JourneyPage.FirstHome) "Z 30" else null,
        title = copy.title,
        titleSize = titleSizeSp(state.page),
        lines = copy.lines,
        callout = copy.callout,
        primary = copy.primary,
        primaryEnabled = if (asksForPassword) typedOk else copy.primary != null,
        secondary = copy.secondary,
        placeholder = copy.placeholder,
        steps = copy.steps,
        stepsBoxed = state.page == JourneyPage.FirstCantFind || state.page == JourneyPage.SecondCantFind,
        foundHeading = if (state.joinSent) copy.foundHeading else null,
        savedHeading = copy.savedHeading,
        savedName = if (copy.savedHeading != null && state.hasSavedPassword && !state.savedSsid.isNullOrBlank()) {
            state.savedSsid
        } else {
            null
        },
        savedAction = copy.savedAction,
        savedActionEnabled = state.hasSavedPassword && !state.savedSsid.isNullOrBlank(),
        hotspots = if (state.page == JourneyPage.SecondReturn) state.seen else emptyList(),
        lastCamera = if (state.page == JourneyPage.SecondAuto) state.savedSsid?.let(::lastCameraLine) else null,
        autoLine = if (state.page == JourneyPage.SecondAuto) SEARCHING_LINE else null,
        rows = if (state.page == JourneyPage.FirstConnecting || state.page == JourneyPage.SecondConnecting) {
            connectingRows(state)
        } else {
            emptyList()
        },
        attempts = state.attempts,
        disconnected = state.disconnected,
        spinner = state.page == JourneyPage.FirstConnecting || state.page == JourneyPage.SecondConnecting,
        dialog = journeyModalTitle(state.page, state.modal)?.let { JourneyDialog(it) },
    )
}

fun primaryEvent(state: JourneyState, now: Long): JourneyEvent? {
    return when (state.page) {
        JourneyPage.FirstHome, JourneyPage.SecondReturn -> JourneyEvent.ConnectCamera(now)
        JourneyPage.FirstCantFind, JourneyPage.SecondCantFind -> JourneyEvent.ContinueConnect
        JourneyPage.FirstPasswordError, JourneyPage.SecondPasswordError -> JourneyEvent.ConnectAgain(now)
        else -> null
    }
}

fun secondaryEvent(state: JourneyState): JourneyEvent? {
    return when (state.page) {
        JourneyPage.FirstConnecting, JourneyPage.SecondConnecting -> JourneyEvent.CancelConnect
        JourneyPage.SecondAuto -> JourneyEvent.CancelAuto
        else -> null
    }
}

fun savedEvent(state: JourneyState, now: Long): JourneyEvent? {
    return if (state.page == JourneyPage.SecondReturn) JourneyEvent.ReconnectLast(now) else null
}

fun systemBack(state: JourneyState): JourneyTransition {
    if (state.modal != JourneyModal.None) return reduce(state, JourneyEvent.Dismiss)
    return JourneyTransition(state)
}
