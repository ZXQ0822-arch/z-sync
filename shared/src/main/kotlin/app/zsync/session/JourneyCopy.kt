package app.zsync.session

data class JourneyCopy(
    val page: JourneyPage,
    val title: String,
    val lines: List<String> = emptyList(),
    val callout: String? = null,
    val primary: String? = null,
    val secondary: String? = null,
    val placeholder: String? = null,
    val foundHeading: String? = null,
    val savedHeading: String? = null,
    val savedAction: String? = null,
    val steps: List<String> = emptyList(),
)

private val firstHomeSteps = listOf(
    "相机开机",
    "按 MENU",
    "点击类似扳手的图标",
    "连接至智能设备",
    "Wi-Fi连接",
    "建立Wi-Fi连接",
)

private val cantFindSteps = listOf(
    "按 MENU",
    "点击类似扳手的图标",
    "连接至智能设备",
    "Wi-Fi连接",
    "建立Wi-Fi连接",
)

private val connectingCopy = JourneyCopy(
    page = JourneyPage.FirstConnecting,
    title = "正在连接",
    foundHeading = "已找到的热点",
    secondary = "取消连接",
)

private val cantFindCopy = JourneyCopy(
    page = JourneyPage.FirstCantFind,
    title = "找不到相机",
    lines = listOf("连接已超时一分钟"),
    callout = "请确认相机Wi-Fi已打开",
    primary = "继续连接",
    steps = cantFindSteps,
)

private val passwordCopy = JourneyCopy(
    page = JourneyPage.FirstPasswordError,
    title = "Wi-Fi 密码错误",
    placeholder = "Wi-Fi 密码",
    primary = "再次连接",
)

fun designFile(page: JourneyPage): String {
    return when (page) {
        JourneyPage.FirstHome -> "01a-home.html"
        JourneyPage.FirstConnecting -> "01b-connecting-back.html"
        JourneyPage.FirstCantFind -> "01c-cant-find-camera.html"
        JourneyPage.FirstPasswordError -> "01d-password-error.html"
        JourneyPage.SecondAuto -> "02a-home.html"
        JourneyPage.SecondReturn -> "02a-home-return.html"
        JourneyPage.SecondConnecting -> "02a-connecting.html"
        JourneyPage.SecondCantFind -> "02c-cant-find-camera.html"
        JourneyPage.SecondPasswordError -> "02d-password-error.html"
        JourneyPage.Photos -> "06-photos.html"
    }
}

fun journeyCopy(page: JourneyPage): JourneyCopy {
    return when (page) {
        JourneyPage.FirstHome -> JourneyCopy(
            page = page,
            title = "连接相机",
            primary = "连接相机",
            placeholder = "请输入相机上的密码",
            steps = firstHomeSteps,
        )
        JourneyPage.FirstConnecting -> connectingCopy
        JourneyPage.SecondConnecting -> connectingCopy.copy(page = page)
        JourneyPage.FirstCantFind -> cantFindCopy
        JourneyPage.SecondCantFind -> cantFindCopy.copy(page = page)
        JourneyPage.FirstPasswordError -> passwordCopy
        JourneyPage.SecondPasswordError -> passwordCopy.copy(page = page)
        JourneyPage.SecondAuto -> JourneyCopy(
            page = page,
            title = "正在自动连接",
            lines = listOf("请确认相机Wi-Fi已开启"),
            secondary = "取消自动连接",
        )
        JourneyPage.SecondReturn -> JourneyCopy(
            page = page,
            title = "自动连接已取消",
            lines = listOf("从下方选择要连接的相机，连接新相机"),
            placeholder = "请输入新相机的密码",
            primary = "连接相机",
            savedHeading = "已保存的相机连接",
            savedAction = "连接",
        )
        JourneyPage.Photos -> JourneyCopy(page = page, title = "照片")
    }
}

fun journeyModalTitle(page: JourneyPage, modal: JourneyModal): String? {
    return when (modal) {
        JourneyModal.None -> null
        JourneyModal.WifiOn -> when (page) {
            JourneyPage.FirstCantFind, JourneyPage.SecondCantFind -> "相机Wi-Fi已开启"
            else -> "相机 Wi-Fi 已开启？"
        }
        JourneyModal.CancelLink -> "是否取消连接"
        JourneyModal.CancelAuto -> "确认取消自动连接？"
    }
}

fun lastCameraLine(ssid: String) = "上次连接 $ssid"

/**
 * Status line while a scan is still looking for the camera, before any real hotspot has been seen.
 * Shown on the auto-connect page and on the connecting page until the target is actually found.
 */
const val SEARCHING_LINE = "正在搜索相机"

fun connectingRows(state: JourneyState): List<String> {
    // Until a real scan has seen the target, the page says it is searching. It must not claim it is
    // connecting to a name that only came from the saved history.
    if (!state.joinSent) return listOf(SEARCHING_LINE)
    val names = LinkedHashSet<String>()
    state.targetSsid?.let(names::add)
    state.seen.forEach(names::add)
    return names.map { name ->
        if (name.equals(state.targetSsid, ignoreCase = true)) "正在连接 $name" else name
    }
}

fun savedCameras(state: JourneyState): List<String> {
    val saved = state.savedSsid
    return if (state.hasSavedPassword && !saved.isNullOrBlank()) listOf(saved) else emptyList()
}
