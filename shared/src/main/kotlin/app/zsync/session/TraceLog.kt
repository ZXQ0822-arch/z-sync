package app.zsync.session

enum class TraceJourney {
    First,
    SecondAuto,
    SecondManual,
}

enum class TraceSurface {
    Home,
    Connecting,
    CantFind,
    Password,
    Return,
    Photos,
}

enum class LeaseCallback {
    Request,
    Available,
    Lost,
    Release,
}

enum class DisconnectCause {
    AndroidLost,
    PtpFirst,
    UserCancel,
    ProcessEnded,
    CameraStandby,
}

sealed interface TraceEvent {
    val traceId: String

    data class Lease(
        override val traceId: String,
        val journey: TraceJourney,
        val surface: TraceSurface,
        val linkState: String,
        val sessionState: String,
        val callback: LeaseCallback,
        val generation: Long,
        val networkId: Long?,
        val ssid: String?,
        val bssid: String?,
        val passwordLength: Int?,
    ) : TraceEvent

    data class Ptp(
        override val traceId: String,
        val operation: String,
        val transactionId: Long,
        val handle: Long,
        val offset: Long,
        val chunkBytes: Long,
    ) : TraceEvent

    data class Recovery(
        override val traceId: String,
        val level: String,
        val attempt: Int,
        val reason: String,
        val elapsedMs: Long,
    ) : TraceEvent

    data class Queue(
        override val traceId: String,
        val taskId: String,
        val safeBytes: Long,
        val result: String,
    ) : TraceEvent

    data class Ended(
        override val traceId: String,
        val cause: DisconnectCause,
    ) : TraceEvent

    data class Note(
        override val traceId: String,
        val text: String,
    ) : TraceEvent
}

fun passwordLength(password: String): Int = password.length

fun freshTraceId(): String = "c-" + java.util.UUID.randomUUID().toString().substringBefore('-')

fun traceJourney(page: JourneyPage): TraceJourney {
    return when (page) {
        JourneyPage.FirstHome,
        JourneyPage.FirstConnecting,
        JourneyPage.FirstCantFind,
        JourneyPage.FirstPasswordError,
        -> TraceJourney.First
        JourneyPage.SecondAuto -> TraceJourney.SecondAuto
        else -> TraceJourney.SecondManual
    }
}

fun traceSurface(page: JourneyPage): TraceSurface {
    return when (page) {
        JourneyPage.FirstHome, JourneyPage.SecondAuto -> TraceSurface.Home
        JourneyPage.FirstConnecting, JourneyPage.SecondConnecting -> TraceSurface.Connecting
        JourneyPage.FirstCantFind, JourneyPage.SecondCantFind -> TraceSurface.CantFind
        JourneyPage.FirstPasswordError, JourneyPage.SecondPasswordError -> TraceSurface.Password
        JourneyPage.SecondReturn -> TraceSurface.Return
        JourneyPage.Photos -> TraceSurface.Photos
    }
}

class SessionTrace(
    private val emit: (String) -> Unit = {},
) {
    var id: String = freshTraceId()
        private set

    private val recorded = mutableListOf<TraceEvent>()

    fun begin() {
        id = freshTraceId()
        recorded.clear()
    }

    fun record(event: TraceEvent) {
        recorded += event
        emit(render(event))
    }

    fun events(): List<TraceEvent> = recorded.toList()

    fun lines(): List<String> = recorded.map(::render)
}

fun sameTrace(events: List<TraceEvent>): Boolean {
    return events.isNotEmpty() && events.map { it.traceId }.distinct().size == 1
}

fun render(event: TraceEvent): String {
    return when (event) {
        is TraceEvent.Lease -> buildString {
            append("trace=${event.traceId}")
            append(" journey=${event.journey}")
            append(" surface=${event.surface}")
            append(" link=${event.linkState}")
            append(" session=${event.sessionState}")
            append(" ssid=${event.ssid ?: "-"}")
            append(" bssid=${event.bssid ?: "-"}")
            append(" network=${event.networkId ?: "-"}")
            append(" lease=${event.generation}")
            append(" callback=${event.callback}")
            append(" passwordLength=${event.passwordLength ?: "-"}")
        }
        is TraceEvent.Ptp ->
            "trace=${event.traceId} operation=${event.operation} transaction=${event.transactionId} handle=${event.handle} offset=${event.offset} chunk=${event.chunkBytes}"
        is TraceEvent.Recovery ->
            "trace=${event.traceId} recovery=${event.level} attempt=${event.attempt} reason=${event.reason} elapsedMs=${event.elapsedMs}"
        is TraceEvent.Queue ->
            "trace=${event.traceId} task=${event.taskId} safeBytes=${event.safeBytes} result=${event.result}"
        is TraceEvent.Ended -> "trace=${event.traceId} cause=${causeText(event.cause)}"
        is TraceEvent.Note -> "trace=${event.traceId} note=${event.text}"
    }
}

fun causeText(cause: DisconnectCause): String {
    return when (cause) {
        DisconnectCause.AndroidLost -> "Android onLost"
        DisconnectCause.PtpFirst -> "PTP closed while the network lease was still held"
        DisconnectCause.UserCancel -> "person confirmed cancel"
        DisconnectCause.ProcessEnded -> "process ended and the lease is gone"
        DisconnectCause.CameraStandby -> "camera standby removed the hotspot"
    }
}
