package app.zsync.session

fun journeyForUi(state: ConnectionState, modal: JourneyModal = JourneyModal.None): JourneyState {
    return state.journey.copy(modal = modal)
}

fun usesJourneyUi(state: ConnectionState, oddFailure: LinkFailure? = null): Boolean {
    if (oddFailure == LinkFailure.OffCamera || oddFailure == LinkFailure.ClientBusy || oddFailure == LinkFailure.Unknown) {
        return false
    }
    if (state.connected()) return false
    return true
}

fun linkPhaseFor(state: ConnectionState, internetAvailable: Boolean, oddFailure: LinkFailure? = null): LinkPhase {
    oddFailure?.let { return LinkPhase.Failed(it) }
    if (state.connected()) {
        return LinkPhase.Connected(state.journey.savedSsid ?: "camera", internetAvailable)
    }
    return when (state.journey.page) {
        JourneyPage.FirstHome -> if (state.journey.hasSavedPassword) LinkPhase.Ready else LinkPhase.NeedPassword
        JourneyPage.SecondReturn -> LinkPhase.Ready
        JourneyPage.FirstConnecting, JourneyPage.SecondConnecting -> LinkPhase.Connecting
        JourneyPage.SecondAuto -> LinkPhase.Searching(0)
        JourneyPage.FirstCantFind, JourneyPage.SecondCantFind -> LinkPhase.WaitingForHotspot
        JourneyPage.FirstPasswordError, JourneyPage.SecondPasswordError -> LinkPhase.Failed(LinkFailure.BadPassword)
        JourneyPage.Photos -> LinkPhase.Connected(state.journey.savedSsid ?: "camera", internetAvailable)
    }
}
