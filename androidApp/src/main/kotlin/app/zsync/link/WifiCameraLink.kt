package app.zsync.link

import app.zsync.log.QuickLog
import android.os.Looper
import android.os.SystemClock
import app.zsync.model.isAcceptableHotspotPassword
import app.zsync.model.isCameraSsid
import app.zsync.session.ConnectionState
import app.zsync.session.ConnectionEvent
import app.zsync.session.JoinRejection
import app.zsync.session.LeaseEvent
import app.zsync.session.LeasePhase
import app.zsync.session.LinkEffect
import app.zsync.session.LinkEvent
import app.zsync.session.LinkPhase
import app.zsync.session.CONNECT_LIMIT_MS
import app.zsync.session.initialConnection
import app.zsync.session.nikonHotspots
import app.zsync.session.scanDrivesTheLink
import app.zsync.session.LinkState
import app.zsync.session.initialLinkState
import app.zsync.session.reduce
import app.zsync.session.reduce as reduceConnection
import app.zsync.session.ConnectionTransition
import app.zsync.session.DisconnectCause
import app.zsync.session.JourneyModal
import app.zsync.session.LeaseCallback
import app.zsync.session.SessionTrace
import app.zsync.session.TraceEvent
import app.zsync.session.listsHotspots
import app.zsync.session.passwordLength
import app.zsync.session.traceJourney
import app.zsync.session.traceSurface
import app.zsync.session.JourneyEffect
import app.zsync.session.JourneyEvent
import app.zsync.session.JourneyPage
import app.zsync.session.LeaseEffect
import app.zsync.session.linkPhaseFor
import app.zsync.session.usesJourneyUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WifiCameraLink(
    private val scope: CoroutineScope,
    private val store: LinkStore,
    private val wifi: AndroidCameraWifi,
    private val onWarmPtp: suspend () -> Boolean,
    private val onCameraConnected: (() -> Unit)? = null,
    private val trace: SessionTrace = SessionTrace(),
) : CameraLink {
    private var machine: LinkState = initialLinkState(store.ssid, store.password)
    private val phases = MutableStateFlow(machine.phase)
    private val saved = MutableStateFlow(machine.memory.hasSavedPassword)
    private val queue = ArrayDeque<LinkEvent>()
    private var draining = false
    private var attemptPassword: String? = null
    private var attemptSsid: String? = null
    private var scanJob: Job? = null
    private var joinJob: Job? = null
    private var deadlineJob: Job? = null
    private var attemptToken = 0
    private var lastScanAt = 0L
    private val lastCameraSsid = MutableStateFlow(store.ssid)
    private val hotspots = MutableStateFlow<List<String>>(emptyList())
    private var coordinator = initialConnection(
        store.ssid,
        !store.password.isNullOrEmpty(),
    )
    private val connections = MutableStateFlow(coordinator)
    private var ptpJob: Job? = null
    private var oddFailure: LinkPhase.Failed? = null

    override val phase: StateFlow<LinkPhase> = phases.asStateFlow()
    override val connection: StateFlow<ConnectionState> = connections.asStateFlow()
    override val passwordSaved: StateFlow<Boolean> = saved.asStateFlow()
    override val lastCamera: StateFlow<String?> = lastCameraSsid.asStateFlow()
    override val seenHotspots: StateFlow<List<String>> = hotspots.asStateFlow()

    init {
        store.guid()
        wifi.onCameraLost = { dispatch(LinkEvent.NetworkLost) }
    }

    override fun screenOpened() {
        applyUser(JourneyEvent.Opened(SystemClock.elapsedRealtime()))
        dispatch(LinkEvent.ScreenOpened)
    }

    override fun user(event: JourneyEvent) = applyUser(event)

    override fun submitPassword(password: String) {
        applyUser(JourneyEvent.PasswordChanged(password))
        dispatch(LinkEvent.PasswordSubmitted(password))
    }

    override fun connect() = dispatch(LinkEvent.ConnectTapped)

    override fun cameraGone() {
        QuickLog.e(LOG_TAG, "ptp failed; Wi-Fi lease kept")
        noteEnded(DisconnectCause.PtpFirst)
        mirrorCoordinator(ConnectionEvent.Network(LeaseEvent.PtpFailed), runEffects = true)
    }

    override fun sessionLost() {
        QuickLog.e(LOG_TAG, "media session lost; going back to the connect screen")
        noteEnded(DisconnectCause.PtpFirst)
        applyUser(JourneyEvent.SessionLost(coordinator.journey.generation, SystemClock.elapsedRealtime()))
    }

    override fun disconnect() {
        QuickLog.i(LOG_TAG, "disconnect requested; releasing the wifi lease")
        scanJob?.cancel()
        ptpJob?.cancel()
        ptpJob = null
        lastScanAt = 0L
        disarmConnectDeadline()
        wifi.release()
        // Reset the coordinator instead of leaving it on Photos/Connecting. The next screen
        // must be the return/home page, not a leftover "connected" state.
        val savedSsid = store.ssid
        val hasPassword = !store.password.isNullOrEmpty()
        coordinator = initialConnection(savedSsid, hasPassword).let { c ->
            c.copy(
                journey = c.journey.copy(
                    page = if (hasPassword) JourneyPage.SecondReturn else JourneyPage.FirstHome,
                ),
            )
        }
        connections.value = coordinator
        dispatch(LinkEvent.Disconnect)
    }

    private fun applyUser(event: JourneyEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) { applyUser(event) }
            return
        }
        mirrorCoordinator(ConnectionEvent.User(event), runEffects = true)
    }

    private fun mirrorCoordinator(event: ConnectionEvent, runEffects: Boolean = true) {
        val user = (event as? ConnectionEvent.User)?.event
        val confirmingCancel = user is JourneyEvent.Confirm &&
            (coordinator.journey.modal == JourneyModal.CancelLink || coordinator.journey.modal == JourneyModal.CancelAuto)
        val transition = reduceConnection(coordinator, event)
        coordinator = transition.state
        connections.value = coordinator
        if (runEffects) runCoordinator(transition)
        if (confirmingCancel) noteEnded(DisconnectCause.UserCancel)
        if (coordinator.connected()) disarmConnectDeadline()
        maybeArmDeadline()
        publishPhase()
    }

    private fun runCoordinator(transition: ConnectionTransition) {
        transition.journeyEffects.forEach { effect ->
            when (effect) {
                JourneyEffect.SCAN -> scheduleScan()
                JourneyEffect.JOIN -> startJoinFromJourney()
                JourneyEffect.RELEASE -> {
                    noteLease(LeaseCallback.Release)
                    disarmConnectDeadline()
                    joinJob?.cancel()
                    joinJob = null
                    dispatch(LinkEvent.Disconnect)
                }
                JourneyEffect.FORGET -> runEffect(LinkEffect.FORGET_PASSWORD)
                JourneyEffect.OPEN_PTP -> beginPtpSession()
            }
        }
        transition.leaseEffects.forEach { effect ->
            if (effect == LeaseEffect.UNREGISTER) wifi.release()
        }
    }

    private fun startJoinFromJourney() {
        if (joinJob?.isActive == true) return
        coordinator.journey.targetSsid?.let { dispatch(LinkEvent.TargetChosen(it)) }
        val typed = coordinator.journey.typedPassword
        if (isAcceptableHotspotPassword(typed.orEmpty())) {
            dispatch(LinkEvent.PasswordSubmitted(typed!!))
            return
        }
        if (coordinator.journey.hasSavedPassword) {
            dispatch(LinkEvent.ConnectTapped)
        }
    }

    private fun publishPhase() {
        val failure = oddFailure?.failure
        val internet = (machine.phase as? LinkPhase.Connected)?.internetAvailable ?: false
        phases.value = if (usesJourneyUi(coordinator, failure)) {
            linkPhaseFor(coordinator, internet, failure)
        } else {
            machine.phase
        }
    }

    private fun dispatch(event: LinkEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) { dispatch(event) }
            return
        }
        queue.add(event)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (next is LinkEvent.PasswordSubmitted && isAcceptableHotspotPassword(next.password)) {
                    attemptPassword = next.password
                }
                val from = machine.phase
                if (next is LinkEvent.NetworkLost) {
                    noteLease(LeaseCallback.Lost)
                    noteEnded(DisconnectCause.AndroidLost)
                    mirrorCoordinator(
                        ConnectionEvent.User(
                            JourneyEvent.NetworkLost(
                                coordinator.journey.generation,
                                SystemClock.elapsedRealtime(),
                            ),
                        ),
                        runEffects = true,
                    )
                    joinJob?.cancel()
                    joinJob = null
                    wifi.release()
                    noteLease(LeaseCallback.Release)
                }
                if (next is LinkEvent.ScanResult) {
                    val seen = nikonHotspots(next.names)
                    hotspots.value = seen
                    mirrorCoordinator(
                        ConnectionEvent.User(
                            JourneyEvent.Scanned(seen, coordinator.journey.generation),
                        ),
                    )
                }
                val transition = reduce(machine, next)
                machine = transition.state
                saved.value = machine.memory.hasSavedPassword
                oddFailure = machine.phase as? LinkPhase.Failed
                if (next is LinkEvent.Disconnect || next is LinkEvent.AttemptExpired) {
                    ptpJob?.cancel()
                    ptpJob = null
                }
                when (next) {
                    is LinkEvent.Joined -> {
                        holdLeaseOnNetwork()
                        mirrorCoordinator(
                            ConnectionEvent.User(
                                JourneyEvent.NetworkAcquired(coordinator.journey.generation),
                            ),
                        )
                    }
                    is LinkEvent.JoinUnavailable -> {
                        when (next.reason) {
                            JoinRejection.WrongPassword -> mirrorCoordinator(
                                ConnectionEvent.User(JourneyEvent.AuthRejected(coordinator.journey.generation)),
                            )
                            JoinRejection.TimedOut -> mirrorCoordinator(
                                ConnectionEvent.User(JourneyEvent.JoinFailed(coordinator.journey.generation)),
                            )
                            JoinRejection.HotspotMissing -> noteEnded(DisconnectCause.CameraStandby)
                            JoinRejection.OffCamera -> Unit
                        }
                    }
                    LinkEvent.AttemptExpired -> {
                        coordinator.journey.deadlineAt?.let { deadline ->
                            mirrorCoordinator(ConnectionEvent.User(JourneyEvent.Tick(deadline)))
                        }
                    }
                    else -> Unit
                }
                val line = "event ${describe(next)}: $from -> ${machine.phase} effects=${transition.effects} $machine"
                if (machine.phase is LinkPhase.Failed) QuickLog.e(LOG_TAG, line) else QuickLog.i(LOG_TAG, line)
                transition.effects.forEach(::runEffect)
                publishPhase()
                if (machine.phase is LinkPhase.Connected ||
                    machine.phase is LinkPhase.Connecting ||
                    machine.phase is LinkPhase.AwaitingSystemAllow
                ) {
                    scanJob?.cancel()
                }
            }
        } finally {
            draining = false
        }
    }

    private fun describe(event: LinkEvent): String = when (event) {
        is LinkEvent.PasswordSubmitted -> "PasswordSubmitted(length=${event.password.length})"
        is LinkEvent.ScanResult -> "ScanResult(count=${event.names.size}, camera=${event.names.any { isCameraSsid(it) }})"
        else -> event.toString()
    }

    private fun runEffect(effect: LinkEffect) {
        when (effect) {
            LinkEffect.SCAN -> scheduleScan()
            LinkEffect.JOIN -> {
                if (listsHotspots(coordinator.journey.page)) {
                    QuickLog.i(LOG_TAG, "join ignored while listing hotspots page=${coordinator.journey.page}")
                    return
                }
                if (joinJob?.isActive == true) {
                    QuickLog.i(LOG_TAG, "join already running, extra tap ignored")
                    return
                }
                acquireLeaseForJoin()
                joinJob = scope.launch(Dispatchers.IO) { join() }
            }
            LinkEffect.CHECK_INTERNET -> scope.launch(Dispatchers.IO) {
                val online = wifi.defaultNetworkHasInternet()
                dispatch(LinkEvent.Internet(online))
            }
            LinkEffect.SAVE_PASSWORD -> {
                val password = attemptPassword ?: machine.pendingPassword
                val ssid = attemptSsid ?: machine.targetSsid
                scope.launch(Dispatchers.IO) {
                    if (password != null) {
                        store.save(ssid, password)
                        if (ssid != null) lastCameraSsid.value = ssid
                        QuickLog.i(LOG_TAG, "password saved length=${password.length} ssid=${ssid ?: "pattern"}")
                    } else {
                        QuickLog.e(LOG_TAG, "password not saved: no password from this attempt")
                    }
                }
            }
            LinkEffect.FORGET_PASSWORD -> scope.launch(Dispatchers.IO) {
                store.clearPassword()
                attemptPassword = null
                QuickLog.i(LOG_TAG, "password cleared after a rejected join")
            }
        }
    }

    private fun armConnectDeadline() {
        deadlineJob?.cancel()
        val token = ++attemptToken
        val deadline = coordinator.journey.deadlineAt
        val waitMs = if (deadline != null) {
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            CONNECT_LIMIT_MS
        }
        deadlineJob = scope.launch {
            delay(waitMs)
            withContext(Dispatchers.Main) {
                if (token != attemptToken) return@withContext
                joinJob?.cancel()
                joinJob = null
                dispatch(LinkEvent.AttemptExpired)
            }
        }
    }

    private fun disarmConnectDeadline() {
        attemptToken++
        deadlineJob?.cancel()
        deadlineJob = null
    }

    /**
     * Start the 60 second attempt as soon as the journey enters a connecting page, not when the
     * camera first answers. Otherwise a camera found late (after the deadline has already passed)
     * arms an expired timer and times the attempt out on the spot.
     */
    private fun maybeArmDeadline() {
        if (coordinator.journey.deadlineAt == null) {
            disarmConnectDeadline()
            return
        }
        if (deadlineJob?.isActive != true) armConnectDeadline()
    }

    private fun scheduleScan() {
        scanJob?.cancel()
        val scans = (machine.phase as? LinkPhase.Searching)?.scans ?: 0
        val first = scans == 0 && lastScanAt == 0L
        scanJob = scope.launch(Dispatchers.IO) {
            if (!first) delay(SCAN_GAP_MS)
            if (!isActive) return@launch
            lastScanAt = SystemClock.elapsedRealtime()
            QuickLog.i(LOG_TAG, "scan begin gap=${if (first) 0 else SCAN_GAP_MS} thread=${Thread.currentThread().name}")
            val names = try {
                wifi.refreshScan()
                wifi.namesFromLastUpdatedScan()
            } catch (failed: Exception) {
                QuickLog.e(LOG_TAG, "scan failed ${failed.javaClass.simpleName}: ${failed.message}")
                emptyList()
            }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                if (machine.phase is LinkPhase.Connected ||
                    machine.phase is LinkPhase.Connecting ||
                    machine.phase is LinkPhase.AwaitingSystemAllow
                ) {
                    return@withContext
                }
                if (!scanDrivesTheLink(coordinator.journey.page)) {
                    // The person is choosing from the hotspot list. Refresh it, but leave the link
                    // machine alone so it does not emit a join the effect runner would ignore.
                    val seen = nikonHotspots(names)
                    hotspots.value = seen
                    mirrorCoordinator(
                        ConnectionEvent.User(JourneyEvent.Scanned(seen, coordinator.journey.generation)),
                    )
                    return@withContext
                }
                dispatch(LinkEvent.ScanResult(names))
            }
        }
    }

    private fun acquireLeaseForJoin() {
        val ssid = machine.targetSsid ?: machine.memory.savedSsid ?: return
        val before = coordinator.lease.generation
        coordinator = reduceConnection(
            coordinator,
            ConnectionEvent.Network(LeaseEvent.Acquire(ssid, null)),
        ).state
        connections.value = coordinator
        if (coordinator.lease.generation != before) trace.begin()
        noteLease(LeaseCallback.Request)
    }

    private fun holdLeaseOnNetwork() {
        val networkId = wifi.cameraNetwork.hashCode().toLong()
        coordinator = reduceConnection(
            coordinator,
            ConnectionEvent.Network(LeaseEvent.Available(coordinator.lease.generation, networkId)),
        ).state
        connections.value = coordinator
        noteLease(LeaseCallback.Available)
    }

    private fun noteLease(callback: LeaseCallback) {
        val journey = coordinator.journey
        val lease = coordinator.lease
        trace.record(
            TraceEvent.Lease(
                traceId = trace.id,
                journey = traceJourney(journey.page),
                surface = traceSurface(journey.page),
                linkState = lease.phase.name,
                sessionState = coordinator.session.name,
                callback = callback,
                generation = lease.generation,
                networkId = lease.networkId,
                ssid = lease.target?.ssid ?: journey.targetSsid ?: journey.savedSsid,
                bssid = lease.target?.bssid,
                passwordLength = attemptPassword?.let(::passwordLength),
            ),
        )
    }

    private fun noteEnded(cause: DisconnectCause) {
        trace.record(TraceEvent.Ended(trace.id, cause))
    }

    private fun beginPtpSession() {
        if (coordinator.connected() || ptpJob?.isActive == true) return
        ptpJob = scope.launch(Dispatchers.IO) {
            val ok = onWarmPtp()
            withContext(Dispatchers.Main) {
                if (ok) {
                    applyUser(JourneyEvent.PtpSessionOpened(coordinator.journey.generation))
                    QuickLog.i(LOG_TAG, "ptp session ready, media page allowed")
                    onCameraConnected?.invoke()
                } else {
                    QuickLog.e(LOG_TAG, "ptp session failed while Wi-Fi was up; lease kept")
                    applyUser(JourneyEvent.PtpSessionFailed(coordinator.journey.generation))
                    mirrorCoordinator(ConnectionEvent.Network(LeaseEvent.PtpFailed), runEffects = true)
                }
            }
        }
    }

    private suspend fun join() {
        val ssid = machine.targetSsid ?: machine.memory.savedSsid
        val password = machine.pendingPassword
            ?: store.password?.takeIf { machine.memory.hasSavedPassword }
        if (password == null) {
            QuickLog.e(LOG_TAG, "join skipped: no typed or saved password")
            return
        }
        QuickLog.i(
            LOG_TAG,
            "join start ssid=${ssid ?: "pattern"} source=${if (machine.pendingPassword != null) "typed" else "saved"} thread=${Thread.currentThread().name}",
        )
        attemptPassword = password
        val ap = wifi.findCamera(ssid)
        if (ap == null && machine.pendingPassword == null) {
            QuickLog.i(LOG_TAG, "camera not in scan, not requesting the network")
            dispatch(LinkEvent.CameraMissed)
            return
        }
        attemptSsid = ap?.ssid ?: ssid
        dispatch(LinkEvent.SystemPromptVisible)
        when (val result = wifi.join(ap, ssid, password)) {
            JoinResult.Up -> dispatch(LinkEvent.Joined)
            JoinResult.Cancelled -> QuickLog.i(LOG_TAG, "join cancelled before the camera answered")
            is JoinResult.Error -> dispatch(LinkEvent.JoinError(result.detail))
            JoinResult.WrongPassword -> {
                QuickLog.i(LOG_TAG, "join rejected: authentication failed")
                dispatch(LinkEvent.JoinUnavailable(JoinRejection.WrongPassword))
            }
            JoinResult.Down -> {
                val here = wifi.currentSsid()
                val target = attemptSsid ?: ssid
                val onCamera = here != null && isCameraSsid(here, target)
                val visible = wifi.namesFromLastUpdatedScan().any { isCameraSsid(it, target) }
                val reason = when {
                    here != null && !onCamera -> JoinRejection.OffCamera
                    !visible -> JoinRejection.HotspotMissing
                    else -> JoinRejection.TimedOut
                }
                QuickLog.i(LOG_TAG, "join rejected current=${here ?: "unknown"} onCamera=$onCamera visible=$visible reason=$reason")
                dispatch(LinkEvent.JoinUnavailable(reason))
            }
        }
    }
}

private const val SCAN_GAP_MS = 1_000L
