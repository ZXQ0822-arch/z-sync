package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JourneyChromeTest {
    @Test
    fun eachJourneyPageHasItsOwnChrome() {
        val pages = JourneyPage.entries.map { journeyChrome(JourneyState(page = it)).page }
        assertEquals(JourneyPage.entries, pages)
    }

    @Test
    fun dialogsAreCenteredWithCancelOnTheLeftAndConfirmOnTheRight() {
        val home = journeyChrome(JourneyState(page = JourneyPage.FirstHome, modal = JourneyModal.WifiOn))
        assertEquals(JourneyDialog("相机 Wi-Fi 已开启？"), home.dialog)
        val miss = journeyChrome(JourneyState(page = JourneyPage.SecondCantFind, modal = JourneyModal.WifiOn))
        assertEquals(JourneyDialog("相机Wi-Fi已开启"), miss.dialog)
        val connecting = journeyChrome(JourneyState(page = JourneyPage.SecondConnecting, modal = JourneyModal.CancelLink))
        assertEquals("取消", connecting.dialog?.left)
        assertEquals("确认", connecting.dialog?.right)
        assertEquals(true, connecting.dialog?.centered)
        val auto = journeyChrome(JourneyState(page = JourneyPage.SecondAuto, modal = JourneyModal.CancelAuto, savedSsid = "NIKON_Z_30_12345"))
        assertEquals("确认取消自动连接？", auto.dialog?.title)
    }

    @Test
    fun theFirstHomeKeepsTheConnectButtonDisabledUntilThePasswordIsLongEnough() {
        val empty = journeyChrome(JourneyState(page = JourneyPage.FirstHome))
        assertEquals("连接相机", empty.title)
        assertEquals("连接相机", empty.primary)
        assertFalse(empty.primaryEnabled)
        assertEquals("请输入相机上的密码", empty.placeholder)
        val ready = journeyChrome(JourneyState(page = JourneyPage.FirstHome, typedPassword = "13579246"))
        assertTrue(ready.primaryEnabled)
        assertEquals(JourneyEvent.ConnectCamera(10), primaryEvent(readyPage(), 10))
    }

    @Test
    fun connectingShowsTheSpinnerAndCancelStaysUntilConfirm() {
        val connecting = JourneyState(
            page = JourneyPage.FirstConnecting,
            targetSsid = "NIKON_Z_30_12345",
            seen = listOf("NIKON_Z_30_12345", "NIKON_Z_30_12346"),
            joinSent = true,
        )
        val chrome = journeyChrome(connecting)
        assertTrue(chrome.spinner)
        assertEquals("取消连接", chrome.secondary)
        assertEquals(listOf("正在连接 NIKON_Z_30_12345", "NIKON_Z_30_12346"), chrome.rows)
        assertEquals(JourneyEvent.CancelConnect, secondaryEvent(connecting))
        val asking = reduce(connecting, JourneyEvent.CancelConnect).state
        val back = systemBack(asking)
        assertEquals(JourneyPage.FirstConnecting, back.state.page)
        assertEquals(JourneyModal.None, back.state.modal)
        assertEquals(emptyList(), back.effects)
        val stayed = systemBack(back.state)
        assertEquals(back.state, stayed.state)
        assertEquals(emptyList(), stayed.effects)
    }

    @Test
    fun connectingSaysItIsSearchingUntilTheHotspotIsActuallySeen() {
        val searching = JourneyState(
            page = JourneyPage.SecondConnecting,
            targetSsid = "NIKON_Z_30_12345",
            savedSsid = "NIKON_Z_30_12345",
            hasSavedPassword = true,
            seen = emptyList(),
            joinSent = false,
        )
        val chrome = journeyChrome(searching)
        assertEquals(listOf("正在搜索相机"), chrome.rows)
        assertEquals(null, chrome.foundHeading)
    }

    @Test
    fun theSecondAutoPageNamesTheSavedCameraAndBackOnlyClosesTheDialog() {
        val auto = JourneyState(page = JourneyPage.SecondAuto, savedSsid = "NIKON_Z_30_12345", hasSavedPassword = true, joinSent = true)
        val chrome = journeyChrome(auto)
        assertEquals("正在自动连接", chrome.title)
        assertEquals(listOf("请确认相机Wi-Fi已开启"), chrome.lines)
        assertEquals("上次连接 NIKON_Z_30_12345", chrome.lastCamera)
        assertEquals("正在搜索相机", chrome.autoLine)
        assertEquals("取消自动连接", chrome.secondary)
        assertFalse(chrome.spinner)
        assertEquals(JourneyEvent.CancelAuto, secondaryEvent(auto))
        val asking = auto.copy(modal = JourneyModal.CancelAuto)
        assertEquals(JourneyPage.SecondAuto, systemBack(asking).state.page)
        assertEquals(JourneyModal.None, systemBack(asking).state.modal)
    }

    @Test
    fun theReturnPageListsTheSearchAndOneSavedCamera() {
        val back = JourneyState(
            page = JourneyPage.SecondReturn,
            savedSsid = "NIKON_Z_30_12345",
            hasSavedPassword = true,
            typedPassword = "24681357",
        )
        val chrome = journeyChrome(back)
        assertEquals("自动连接已取消", chrome.title)
        assertEquals("连接相机", chrome.primary)
        assertTrue(chrome.primaryEnabled)
        assertEquals("已保存的相机连接", chrome.savedHeading)
        assertEquals("NIKON_Z_30_12345", chrome.savedName)
        assertEquals("连接", chrome.savedAction)
        assertTrue(chrome.savedActionEnabled)
        assertEquals(JourneyEvent.ConnectCamera(3), primaryEvent(back, 3))
        assertEquals(JourneyEvent.ReconnectLast(3), savedEvent(back, 3))
        val seen = journeyChrome(back.copy(seen = listOf("NIKON_Z_30_12346")))
        assertEquals(listOf("NIKON_Z_30_12346"), seen.hotspots)
        val locked = journeyChrome(back.copy(hasSavedPassword = false, typedPassword = ""))
        assertFalse(locked.savedActionEnabled)
        assertFalse(locked.primaryEnabled)
    }

    @Test
    fun cantFindAndPasswordPagesUseTheDrawnActions() {
        val missed = JourneyState(page = JourneyPage.FirstCantFind)
        val missChrome = journeyChrome(missed)
        assertEquals("找不到相机", missChrome.title)
        assertEquals(listOf("连接已超时一分钟"), missChrome.lines)
        assertEquals("请确认相机Wi-Fi已打开", missChrome.callout)
        assertEquals("继续连接", missChrome.primary)
        assertTrue(missChrome.primaryEnabled)
        assertEquals(JourneyEvent.ContinueConnect, primaryEvent(missed, 1))
        assertEquals(
            listOf("按 MENU", "点击类似扳手的图标", "连接至智能设备", "Wi-Fi连接", "建立Wi-Fi连接"),
            missChrome.steps,
        )

        val password = JourneyState(page = JourneyPage.SecondPasswordError, typedPassword = "1234")
        assertEquals("Wi-Fi 密码错误", journeyChrome(password).title)
        assertEquals("再次连接", journeyChrome(password).primary)
        assertFalse(journeyChrome(password).primaryEnabled)
        assertEquals(JourneyEvent.ConnectAgain(8), primaryEvent(password, 8))
        assertNull(secondaryEvent(password))
        assertNull(savedEvent(password, 8))
    }

    @Test
    fun onlyTheMissPagesBoxTheirSteps() {
        assertTrue(journeyChrome(JourneyState(page = JourneyPage.FirstCantFind)).stepsBoxed)
        assertTrue(journeyChrome(JourneyState(page = JourneyPage.SecondCantFind)).stepsBoxed)
        assertFalse(journeyChrome(JourneyState(page = JourneyPage.FirstHome)).stepsBoxed)
    }

    @Test
    fun theSavedCameraRowOnlyShowsOnTheReturnPage() {
        val saved = JourneyState(savedSsid = "NIKON_Z_30_12345", hasSavedPassword = true)
        assertEquals("NIKON_Z_30_12345", journeyChrome(saved.copy(page = JourneyPage.SecondReturn)).savedName)
        assertNull(journeyChrome(saved.copy(page = JourneyPage.SecondAuto)).savedName)
        assertNull(journeyChrome(saved.copy(page = JourneyPage.SecondConnecting)).savedName)
        assertNull(journeyChrome(saved.copy(page = JourneyPage.SecondCantFind)).savedName)
        assertNull(journeyChrome(saved.copy(page = JourneyPage.FirstHome)).savedName)
    }

    @Test
    fun theDisconnectNoticeComesFromTheState() {
        val saved = JourneyState(page = JourneyPage.SecondAuto, savedSsid = "NIKON_Z_30_12345", hasSavedPassword = true)
        assertFalse(journeyChrome(saved).disconnected)
        assertTrue(journeyChrome(saved.copy(disconnected = true)).disconnected)
    }

    @Test
    fun titleSizesMatchTheDesignPages() {
        assertEquals(28, titleSizeSp(JourneyPage.FirstHome))
        assertEquals(28, titleSizeSp(JourneyPage.SecondAuto))
        assertEquals(22, titleSizeSp(JourneyPage.FirstConnecting))
        assertEquals(22, titleSizeSp(JourneyPage.SecondConnecting))
        assertEquals(22, titleSizeSp(JourneyPage.FirstCantFind))
        assertEquals(22, titleSizeSp(JourneyPage.SecondCantFind))
        assertEquals(22, titleSizeSp(JourneyPage.FirstPasswordError))
        assertEquals(22, titleSizeSp(JourneyPage.SecondPasswordError))
        assertEquals(22, titleSizeSp(JourneyPage.SecondReturn))
    }

    @Test
    fun confirmingCancelReturnsToTheMatchingHome() {
        val first = JourneyState(page = JourneyPage.FirstConnecting, modal = JourneyModal.CancelLink, generation = 2, joinSent = true)
        val home = reduce(first, JourneyEvent.Confirm(1))
        assertEquals(JourneyPage.FirstHome, home.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), home.effects)

        val second = JourneyState(page = JourneyPage.SecondConnecting, modal = JourneyModal.CancelLink, generation = 2, joinSent = true, hasSavedPassword = true, savedSsid = "NIKON_Z_30_12345")
        val returned = reduce(second, JourneyEvent.Confirm(1))
        assertEquals(JourneyPage.SecondReturn, returned.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), returned.effects)
    }
}

private fun readyPage() = JourneyState(page = JourneyPage.FirstHome, typedPassword = "13579246")
