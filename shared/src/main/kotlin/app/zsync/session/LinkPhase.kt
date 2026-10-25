package app.zsync.session

enum class LinkFailure {
    HotspotOff,
    OffCamera,
    BadPassword,
    ClientBusy,
    Dropped,
    Unknown,
}

enum class JoinRejection {
    WrongPassword,
    OffCamera,
    HotspotMissing,
    TimedOut,
}

sealed interface LinkPhase {
    data object Ready : LinkPhase

    data object WaitingForHotspot : LinkPhase

    data class Searching(val scans: Int) : LinkPhase

    data object NeedPassword : LinkPhase

    data object AwaitingSystemAllow : LinkPhase

    data object Connecting : LinkPhase

    data class Connected(val ssid: String, val internetAvailable: Boolean) : LinkPhase

    data class Failed(val failure: LinkFailure, val detail: String? = null) : LinkPhase
}

fun releasesConnectCooldown(phase: LinkPhase): Boolean = phase is LinkPhase.Failed

fun showsWaitSpinner(phase: LinkPhase): Boolean =
    phase is LinkPhase.AwaitingSystemAllow || phase is LinkPhase.Connecting

fun showsSystemAllowButton(phase: LinkPhase): Boolean = false

fun homeActionLabel(passwordSaved: Boolean, typed: String): String =
    if (passwordSaved && typed.isBlank()) "重新连接" else "连接"

const val SCAN_HINT = "相机可能没开机或不在旁边，请打开相机的 Wi-Fi，连接后再试。"

fun scanCountLabel(scans: Int): String {
    if (scans <= 0) return "正在扫描"
    val count = when (scans) {
        1 -> "一次"
        2 -> "两次"
        3 -> "三次"
        4 -> "四次"
        5 -> "五次"
        6 -> "六次"
        7 -> "七次"
        8 -> "八次"
        9 -> "九次"
        10 -> "十次"
        else -> "${scans}次"
    }
    return "扫描$count"
}
