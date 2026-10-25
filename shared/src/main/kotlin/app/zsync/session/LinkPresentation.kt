package app.zsync.session

fun usesJourneyPresentation(phase: LinkPhase, mediaReady: Boolean = true): Boolean {
    return when (phase) {
        is LinkPhase.Connected -> !mediaReady
        is LinkPhase.Failed -> phase.failure == LinkFailure.BadPassword ||
            phase.failure == LinkFailure.HotspotOff ||
            phase.failure == LinkFailure.Dropped
        else -> true
    }
}

fun presentedJourney(
    phase: LinkPhase,
    passwordSaved: Boolean,
    savedSsid: String?,
    typedPassword: String?,
    modal: JourneyModal = JourneyModal.None,
    seen: List<String> = emptyList(),
    mediaReady: Boolean = true,
): JourneyState {
    val saved = savedSsid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    val cameras = nikonHotspots(seen)
    val page = when (phase) {
        is LinkPhase.Connected -> if (mediaReady) JourneyPage.Photos else {
            if (passwordSaved) JourneyPage.SecondConnecting else JourneyPage.FirstConnecting
        }
        LinkPhase.Ready -> if (passwordSaved) JourneyPage.SecondReturn else JourneyPage.FirstHome
        LinkPhase.NeedPassword -> JourneyPage.FirstHome
        LinkPhase.WaitingForHotspot -> if (passwordSaved) JourneyPage.SecondCantFind else JourneyPage.FirstCantFind
        is LinkPhase.Searching -> if (passwordSaved) JourneyPage.SecondAuto else JourneyPage.FirstConnecting
        LinkPhase.Connecting, LinkPhase.AwaitingSystemAllow ->
            if (passwordSaved) JourneyPage.SecondConnecting else JourneyPage.FirstConnecting
        is LinkPhase.Failed -> when (phase.failure) {
            LinkFailure.BadPassword -> if (passwordSaved) JourneyPage.SecondPasswordError else JourneyPage.FirstPasswordError
            else -> if (passwordSaved) JourneyPage.SecondCantFind else JourneyPage.FirstCantFind
        }
    }
    val joining = page == JourneyPage.SecondAuto ||
        page == JourneyPage.FirstConnecting ||
        page == JourneyPage.SecondConnecting
    return JourneyState(
        page = page,
        modal = modal,
        savedSsid = saved,
        hasSavedPassword = passwordSaved,
        typedPassword = typedPassword,
        seen = cameras,
        targetSsid = if (joining) saved ?: cameras.firstOrNull() else null,
        joinSent = joining,
    )
}
