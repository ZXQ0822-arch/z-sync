package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JourneyTest {
    @Test
    fun theFirstHomeListsNikonHotspotsAndDoesNotJoin() {
        val opened = reduce(initialJourney(savedSsid = null, hasSavedPassword = false), JourneyEvent.Opened(1_000))
        assertEquals(JourneyPage.FirstHome, opened.state.page)
        assertEquals(listOf(JourneyEffect.SCAN), opened.effects)
        assertFalse(joins(opened.state.page))

        val scanned = reduce(
            opened.state,
            JourneyEvent.Scanned(
                listOf("HOME_NET_01", "\"NIKON_Z_30_12345\"", "nikon_z_30_9", "NIKON_Z_30_12345", "Canon_R50", "  "),
                opened.state.generation,
            ),
        )
        assertEquals(listOf("NIKON_Z_30_12345", "nikon_z_30_9"), scanned.state.seen)
        assertEquals(listOf(JourneyEffect.SCAN), scanned.effects)
        assertEquals(emptyList(), savedCameras(scanned.state))
    }

    @Test
    fun aShortPasswordDoesNotAskAndTheLengthBoundsMatchTheHotspotRule() {
        val home = firstHome()
        assertEquals(home.state, reduce(home.state, JourneyEvent.ConnectCamera(1_000)).state)
        val short = home.state.copy(typedPassword = "1234567")
        assertEquals(short, reduce(short, JourneyEvent.ConnectCamera(1_000)).state)
        val huge = home.state.copy(typedPassword = "x".repeat(64))
        assertEquals(huge, reduce(huge, JourneyEvent.ConnectCamera(1_000)).state)

        val asked = reduce(home.state.copy(typedPassword = "12345678"), JourneyEvent.ConnectCamera(1_000))
        assertEquals(JourneyModal.WifiOn, asked.state.modal)
        assertEquals(JourneyPage.FirstHome, asked.state.page)
        assertEquals(emptyList(), asked.effects)

        val long = reduce(home.state.copy(typedPassword = "x".repeat(63)), JourneyEvent.ConnectCamera(1_000))
        assertEquals(JourneyModal.WifiOn, long.state.modal)
    }

    @Test
    fun dismissingTheWifiPromptStaysOnTheFirstHomeAndKeepsSearching() {
        val asked = askToConnect(firstHome().state, "13579246")
        val stayed = reduce(asked.state, JourneyEvent.Dismiss)
        assertEquals(JourneyPage.FirstHome, stayed.state.page)
        assertEquals(JourneyModal.None, stayed.state.modal)
        assertNull(stayed.state.deadlineAt)
        assertEquals(emptyList(), stayed.effects)

        val still = reduce(stayed.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), stayed.state.generation))
        assertEquals(listOf(JourneyEffect.SCAN), still.effects)
        assertEquals(JourneyPage.FirstHome, still.state.page)
    }

    @Test
    fun confirmingTheFirstHomeJoinsACameraAlreadyOnScreen() {
        val seen = firstHome().state.copy(seen = listOf("NIKON_Z_30_12345"), typedPassword = "13579246")
        val started = reduce(reduce(seen, JourneyEvent.ConnectCamera(1_000)).state, JourneyEvent.Confirm(1_000))
        assertEquals(JourneyPage.FirstConnecting, started.state.page)
        assertEquals(JourneyModal.None, started.state.modal)
        assertEquals("NIKON_Z_30_12345", started.state.targetSsid)
        assertFalse(started.state.followSaved)
        assertEquals(61_000, started.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), started.effects)
        assertTrue(joins(started.state.page))
    }

    @Test
    fun theFirstAttemptJoinsOnceWhenTheHotspotAppearsLater() {
        val started = reduce(askToConnect(firstHome().state, "13579246").state, JourneyEvent.Confirm(1_000))
        assertEquals(listOf(JourneyEffect.SCAN), started.effects)
        assertFalse(started.state.joinSent)
        assertEquals(listOf("正在搜索相机"), connectingRows(started.state))

        val found = reduce(started.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345", "NIKON_Z_30_12346"), started.state.generation))
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), found.effects)
        assertEquals("NIKON_Z_30_12345", found.state.targetSsid)
        assertEquals(listOf("正在连接 NIKON_Z_30_12345", "NIKON_Z_30_12346"), connectingRows(found.state))

        val again = reduce(found.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345", "NIKON_Z_30_12346"), found.state.generation))
        assertEquals(listOf(JourneyEffect.SCAN), again.effects)
        assertTrue(again.state.joinSent)
    }

    @Test
    fun cancelKeepsTheAttemptUntilConfirmAndThenReleasesOnce() {
        val connecting = connectingFirst()
        val old = connecting.generation
        val asking = reduce(connecting, JourneyEvent.CancelConnect)
        assertEquals(JourneyModal.CancelLink, asking.state.modal)
        assertEquals(JourneyPage.FirstConnecting, asking.state.page)
        assertEquals(connecting.deadlineAt, asking.state.deadlineAt)
        assertTrue(asking.state.joinSent)
        assertEquals(emptyList(), asking.effects)

        val stayed = reduce(asking.state, JourneyEvent.Dismiss)
        assertEquals(JourneyPage.FirstConnecting, stayed.state.page)
        assertEquals(JourneyModal.None, stayed.state.modal)
        assertEquals(emptyList(), stayed.effects)
        assertEquals(old, stayed.state.generation)

        val askingAgain = reduce(stayed.state, JourneyEvent.CancelConnect)
        val home = reduce(askingAgain.state, JourneyEvent.Confirm(2_000))
        assertEquals(JourneyPage.FirstHome, home.state.page)
        assertEquals(JourneyModal.None, home.state.modal)
        assertNull(home.state.deadlineAt)
        assertFalse(home.state.joinSent)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), home.effects)

        val second = reduce(home.state, JourneyEvent.Confirm(2_000))
        assertEquals(home.state, second.state)
        assertEquals(emptyList(), second.effects)

        val back = reduce(home.state, JourneyEvent.Dismiss)
        assertEquals(home.state, back.state)
    }

    @Test
    fun staleWorkFromTheCancelledAttemptCannotJoinOrOpenPhotos() {
        val connecting = connectingFirst()
        val old = connecting.generation
        val home = reduce(reduce(connecting, JourneyEvent.CancelConnect).state, JourneyEvent.Confirm(2_000))
        listOf(
            JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), old),
            JourneyEvent.AuthRejected(old),
            JourneyEvent.HotspotAbsent(old),
            JourneyEvent.NetworkAcquired(old),
            JourneyEvent.PtpSessionOpened(old),
            JourneyEvent.PtpSessionFailed(old),
            JourneyEvent.NetworkLost(old, 0L),
        ).forEach { event ->
            val ignored = reduce(home.state, event)
            assertEquals(home.state, ignored.state)
            assertEquals(emptyList(), ignored.effects)
        }

        val fresh = reduce(home.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), home.state.generation))
        assertEquals(JourneyPage.FirstHome, fresh.state.page)
        assertEquals(listOf(JourneyEffect.SCAN), fresh.effects)
    }

    @Test
    fun onlyAnExplicitAuthFailureLeavesTheAttemptForThePasswordPage() {
        val connecting = connectingFirst()
        val absent = reduce(connecting, JourneyEvent.HotspotAbsent(connecting.generation))
        assertEquals(connecting, absent.state)
        assertEquals(emptyList(), absent.effects)
        assertTrue(absent.state.hasSavedPassword || absent.state.typedPassword == "13579246")

        val ptp = reduce(connecting, JourneyEvent.PtpSessionFailed(connecting.generation))
        assertEquals(connecting, ptp.state)
        assertEquals(emptyList(), ptp.effects)

        val lost = reduce(connecting, JourneyEvent.NetworkLost(connecting.generation, 2_000))
        assertEquals(JourneyPage.FirstCantFind, lost.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), lost.effects)

        val rejected = reduce(connecting, JourneyEvent.AuthRejected(connecting.generation))
        assertEquals(JourneyPage.FirstPasswordError, rejected.state.page)
        assertFalse(rejected.state.hasSavedPassword)
        assertNull(rejected.state.typedPassword)
        assertEquals("NIKON_Z_30_12345", rejected.state.savedSsid ?: rejected.state.targetSsid)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.FORGET), rejected.effects)

        val duplicate = reduce(rejected.state, JourneyEvent.AuthRejected(connecting.generation))
        assertEquals(rejected.state, duplicate.state)
        assertEquals(emptyList(), duplicate.effects)
    }

    @Test
    fun photosOpenOnlyAfterTheNetworkLeaseAndThePtpSession() {
        val connecting = connectingFirst()
        val tooSoon = reduce(connecting, JourneyEvent.PtpSessionOpened(connecting.generation))
        assertEquals(JourneyPage.FirstConnecting, tooSoon.state.page)
        assertEquals(emptyList(), tooSoon.effects)

        val leased = reduce(connecting, JourneyEvent.NetworkAcquired(connecting.generation))
        assertEquals(JourneyPage.FirstConnecting, leased.state.page)
        assertEquals(listOf(JourneyEffect.OPEN_PTP), leased.effects)
        val repeat = reduce(leased.state, JourneyEvent.NetworkAcquired(leased.state.generation))
        assertEquals(leased.state, repeat.state)
        assertEquals(emptyList(), repeat.effects)

        val photos = reduce(leased.state, JourneyEvent.PtpSessionOpened(leased.state.generation))
        assertEquals(JourneyPage.Photos, photos.state.page)
        assertTrue(photos.state.ptpOpen)
        assertTrue(photos.state.hasSavedPassword)
        assertEquals("NIKON_Z_30_12345", photos.state.savedSsid)
        assertNull(photos.state.typedPassword)
        assertEquals(emptyList(), photos.effects)
        assertFalse(photos.state.toString().contains("13579246"))

        val late = reduce(photos.state, JourneyEvent.AuthRejected(connecting.generation))
        assertEquals(photos.state, late.state)
    }

    @Test
    fun theFirstAttemptTimesOutOnItsOwnClockEvenWhileCancelIsAsking() {
        val connecting = connectingFirst()
        assertEquals(CONNECT_LIMIT_MS, 60_000L)
        val early = reduce(connecting, JourneyEvent.Tick(connecting.deadlineAt!! - 1))
        assertEquals(connecting, early.state)

        val leased = reduce(connecting, JourneyEvent.NetworkAcquired(connecting.generation)).state
        val asking = reduce(leased, JourneyEvent.CancelConnect).state
        val missed = reduce(asking, JourneyEvent.Tick(leased.deadlineAt!!))
        assertEquals(JourneyPage.FirstCantFind, missed.state.page)
        assertEquals(JourneyModal.None, missed.state.modal)
        assertNull(missed.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), missed.effects)

        val quiet = reduce(missed.state, JourneyEvent.Tick(leased.deadlineAt!! + 10_000))
        assertEquals(missed.state, quiet.state)
    }

    @Test
    fun continuingFromTheFirstMissPageStartsAFreshMinuteOnTheFirstAttempt() {
        val missed = reduce(connectingFirst(), JourneyEvent.Tick(61_000)).state
        assertEquals(listOf("按 MENU", "点击类似扳手的图标", "连接至智能设备", "Wi-Fi连接", "建立Wi-Fi连接"), journeyCopy(missed.page).steps)
        val looking = reduce(missed, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), missed.generation))
        assertEquals(listOf(JourneyEffect.SCAN), looking.effects)

        val asking = reduce(looking.state, JourneyEvent.ContinueConnect)
        assertEquals(JourneyModal.WifiOn, asking.state.modal)
        assertEquals(JourneyPage.FirstCantFind, asking.state.page)
        assertEquals(emptyList(), asking.effects)
        val stayed = reduce(asking.state, JourneyEvent.Dismiss)
        assertEquals(JourneyPage.FirstCantFind, stayed.state.page)

        val again = reduce(asking.state, JourneyEvent.Confirm(80_000))
        assertEquals(JourneyPage.FirstConnecting, again.state.page)
        assertEquals(140_000, again.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), again.effects)
    }

    @Test
    fun theFirstPasswordPageRetriesTheFirstAttemptAndCancelReturnsHome() {
        val rejected = reduce(connectingFirst(), JourneyEvent.AuthRejected(connectingFirst().generation))
        val short = rejected.state.copy(typedPassword = "1234")
        assertEquals(short, reduce(short, JourneyEvent.ConnectAgain(5_000)).state)

        val typed = rejected.state.copy(typedPassword = "87654321")
        val again = reduce(typed, JourneyEvent.ConnectAgain(5_000))
        assertEquals(JourneyPage.FirstConnecting, again.state.page)
        assertEquals(65_000, again.state.deadlineAt)
        assertFalse(again.state.followSaved)

        val rejectedAgain = reduce(again.state, JourneyEvent.AuthRejected(again.state.generation))
        assertEquals(JourneyPage.FirstPasswordError, rejectedAgain.state.page)

        val retried = reduce(rejectedAgain.state.copy(typedPassword = "11112222"), JourneyEvent.ConnectAgain(9_000)).state
        val home = reduce(reduce(retried, JourneyEvent.CancelConnect).state, JourneyEvent.Confirm(9_100))
        assertEquals(JourneyPage.FirstHome, home.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), home.effects)
    }

    @Test
    fun theSecondOpenJoinsOnlyTheSavedCamera() {
        val opened = reduce(initialJourney("NIKON_Z_30_12345", hasSavedPassword = true), JourneyEvent.Opened(1_000))
        assertEquals(JourneyPage.SecondAuto, opened.state.page)
        assertTrue(opened.state.followSaved)
        assertEquals(61_000, opened.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.SCAN), opened.effects)

        val other = reduce(opened.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12346"), opened.state.generation))
        assertEquals(listOf(JourneyEffect.SCAN), other.effects)
        assertFalse(other.state.joinSent)

        val saved = reduce(other.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12346", "NIKON_Z_30_12345"), other.state.generation))
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), saved.effects)
        assertEquals("NIKON_Z_30_12345", saved.state.targetSsid)

        val once = reduce(saved.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), saved.state.generation))
        assertEquals(listOf(JourneyEffect.SCAN), once.effects)
    }

    @Test
    fun aSavedCameraAlreadyOnScreenJoinsAsSoonAsTheSecondOpenStarts() {
        val ready = initialJourney("NIKON_Z_30_12345", hasSavedPassword = true).copy(seen = listOf("NIKON_Z_30_12345"))
        val opened = reduce(ready, JourneyEvent.Opened(1_000))
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), opened.effects)
        assertEquals(JourneyPage.SecondAuto, opened.state.page)
    }

    @Test
    fun theSecondOpenReachesPhotosOnlyWhenWifiAndPtpBothSucceed() {
        val auto = secondAutoJoined()
        val leased = reduce(auto, JourneyEvent.NetworkAcquired(auto.generation))
        assertEquals(JourneyPage.SecondAuto, leased.state.page)
        val photos = reduce(leased.state, JourneyEvent.PtpSessionOpened(leased.state.generation))
        assertEquals(JourneyPage.Photos, photos.state.page)
        assertEquals("NIKON_Z_30_12345", photos.state.savedSsid)
    }

    @Test
    fun theSecondOpenTimesOutOrRejectsThePasswordOntoTheSecondPages() {
        val auto = secondAutoJoined()
        val missed = reduce(auto, JourneyEvent.Tick(auto.deadlineAt!!))
        assertEquals(JourneyPage.SecondCantFind, missed.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), missed.effects)

        val rejected = reduce(auto, JourneyEvent.AuthRejected(auto.generation))
        assertEquals(JourneyPage.SecondPasswordError, rejected.state.page)
        assertFalse(rejected.state.hasSavedPassword)
        assertEquals("NIKON_Z_30_12345", rejected.state.savedSsid)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.FORGET), rejected.effects)
        assertEquals(rejected.state, reduce(rejected.state, JourneyEvent.ReconnectLast(4_000)).state)
    }

    @Test
    fun cancellingAutoConnectReleasesOnceAndLandsOnTheReturnPage() {
        val auto = secondAutoJoined()
        val asking = reduce(auto, JourneyEvent.CancelAuto)
        assertEquals(JourneyModal.CancelAuto, asking.state.modal)
        assertEquals(JourneyPage.SecondAuto, asking.state.page)
        assertTrue(asking.state.joinSent)
        assertEquals(emptyList(), asking.effects)

        val stayed = reduce(asking.state, JourneyEvent.Dismiss)
        assertEquals(JourneyPage.SecondAuto, stayed.state.page)
        assertTrue(stayed.state.joinSent)

        val back = reduce(asking.state, JourneyEvent.Confirm(2_000))
        assertEquals(JourneyPage.SecondReturn, back.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), back.effects)
        assertEquals(back.state, reduce(back.state, JourneyEvent.Confirm(2_000)).state)

        val listed = reduce(back.state, JourneyEvent.Scanned(listOf("Canon_R50", "NIKON_Z_30_12345"), back.state.generation))
        assertEquals(JourneyPage.SecondReturn, listed.state.page)
        assertEquals(listOf("NIKON_Z_30_12345"), listed.state.seen)
        assertEquals(listOf(JourneyEffect.SCAN), listed.effects)
        assertEquals(listOf("NIKON_Z_30_12345"), savedCameras(listed.state))
    }

    @Test
    fun theReturnPageReconnectsTheSavedCameraOrStartsANewOne() {
        val back = returnPage()
        val locked = back.state.copy(hasSavedPassword = false)
        assertEquals(locked, reduce(locked, JourneyEvent.ReconnectLast(3_000)).state)
        val short = back.state.copy(typedPassword = "1234")
        assertEquals(short, reduce(short, JourneyEvent.ConnectCamera(3_000)).state)

        val saved = reduce(back.state, JourneyEvent.ReconnectLast(3_000))
        assertEquals(JourneyPage.SecondConnecting, saved.state.page)
        assertTrue(saved.state.followSaved)
        assertEquals("NIKON_Z_30_12345", saved.state.targetSsid)
        assertEquals(63_000, saved.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), saved.effects)

        val found = back.state.copy(seen = listOf("NIKON_Z_30_12346"), typedPassword = "24681357")
        val fresh = reduce(found, JourneyEvent.ConnectCamera(4_000))
        assertEquals(JourneyPage.SecondConnecting, fresh.state.page)
        assertFalse(fresh.state.followSaved)
        assertEquals("NIKON_Z_30_12346", fresh.state.targetSsid)
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), fresh.effects)
    }

    @Test
    fun aManualSecondAttemptCancelsToTheReturnPageAndTimesOutToTheSecondMissPage() {
        val connecting = reduce(returnPage().state, JourneyEvent.ReconnectLast(3_000)).state
        val home = reduce(reduce(connecting, JourneyEvent.CancelConnect).state, JourneyEvent.Confirm(3_500))
        assertEquals(JourneyPage.SecondReturn, home.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), home.effects)

        val started = reduce(returnPage().state, JourneyEvent.ReconnectLast(3_000)).state
        val missed = reduce(started, JourneyEvent.Tick(started.deadlineAt!!))
        assertEquals(JourneyPage.SecondCantFind, missed.state.page)
        val again = reduce(reduce(missed.state, JourneyEvent.ContinueConnect).state, JourneyEvent.Confirm(90_000))
        assertEquals(JourneyPage.SecondConnecting, again.state.page)
        assertTrue(again.state.followSaved)
        assertEquals(150_000, again.state.deadlineAt)
    }

    @Test
    fun aNewCameraOverwritesTheSavedOneOnlyAfterWifiAndPtpSucceed() {
        val found = returnPage().state.copy(seen = listOf("NIKON_Z_30_12346"), typedPassword = "24681357")
        val connecting = reduce(found, JourneyEvent.ConnectCamera(4_000)).state
        val leased = reduce(connecting, JourneyEvent.NetworkAcquired(connecting.generation)).state
        val photos = reduce(leased, JourneyEvent.PtpSessionOpened(leased.generation))
        assertEquals(JourneyPage.Photos, photos.state.page)
        assertEquals("NIKON_Z_30_12346", photos.state.savedSsid)
        assertTrue(photos.state.hasSavedPassword)
        assertEquals(listOf("NIKON_Z_30_12346"), savedCameras(photos.state))
    }

    @Test
    fun theSecondPasswordPageRetriesTheSecondAttemptAndCancelReturnsToTheReturnPage() {
        val auto = secondAutoJoined()
        val rejected = reduce(auto, JourneyEvent.AuthRejected(auto.generation)).state
        val again = reduce(rejected.copy(typedPassword = "99998888"), JourneyEvent.ConnectAgain(8_000))
        assertEquals(JourneyPage.SecondConnecting, again.state.page)
        assertTrue(again.state.followSaved)
        assertEquals("NIKON_Z_30_12345", again.state.targetSsid)

        val rejectedAgain = reduce(again.state, JourneyEvent.AuthRejected(again.state.generation))
        assertEquals(JourneyPage.SecondPasswordError, rejectedAgain.state.page)

        val retried = reduce(rejectedAgain.state.copy(typedPassword = "99998888"), JourneyEvent.ConnectAgain(9_000)).state
        val back = reduce(reduce(retried, JourneyEvent.CancelConnect).state, JourneyEvent.Confirm(9_100))
        assertEquals(JourneyPage.SecondReturn, back.state.page)
    }

    @Test
    fun browsingAndPasswordPagesNeverJoin() {
        val browsing = listOf(
            JourneyPage.FirstHome,
            JourneyPage.FirstCantFind,
            JourneyPage.SecondReturn,
            JourneyPage.SecondCantFind,
        )
        browsing.forEach { page ->
            val state = JourneyState(
                page = page,
                generation = 4,
                hasSavedPassword = true,
                savedSsid = "NIKON_Z_30_12345",
                seen = listOf("NIKON_Z_30_12345"),
                followSaved = true,
            )
            val next = reduce(state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), 4))
            assertEquals(page, next.state.page)
            assertEquals(listOf(JourneyEffect.SCAN), next.effects)
            assertFalse(joins(page))
        }
        listOf(JourneyPage.FirstPasswordError, JourneyPage.SecondPasswordError, JourneyPage.Photos).forEach { page ->
            val state = JourneyState(page = page, generation = 4, typedPassword = "13579246", seen = listOf("NIKON_Z_30_12345"))
            val next = reduce(state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), 4))
            assertEquals(state, next.state)
            assertEquals(emptyList(), next.effects)
        }
    }

    @Test
    fun anOpenWithoutASavedCameraStaysOnTheFirstHome() {
        val opened = reduce(initialJourney(savedSsid = " ", hasSavedPassword = true), JourneyEvent.Opened(1_000))
        assertEquals(JourneyPage.FirstHome, opened.state.page)
        assertEquals(listOf(JourneyEffect.SCAN), opened.effects)
        val unnamed = reduce(initialJourney(savedSsid = null, hasSavedPassword = true), JourneyEvent.Opened(1_000))
        assertEquals(JourneyPage.FirstHome, unnamed.state.page)
    }

    @Test
    fun everyDrawnScreenHasTheCopyFromItsHtmlPage() {
        val firstHome = journeyCopy(JourneyPage.FirstHome)
        assertEquals("连接相机", firstHome.title)
        assertEquals("连接相机", firstHome.primary)
        assertEquals("请输入相机上的密码", firstHome.placeholder)
        assertEquals(listOf("相机开机", "按 MENU", "点击类似扳手的图标", "连接至智能设备", "Wi-Fi连接", "建立Wi-Fi连接"), firstHome.steps)
        assertEquals("相机 Wi-Fi 已开启？", journeyModalTitle(JourneyPage.FirstHome, JourneyModal.WifiOn))

        val connecting = journeyCopy(JourneyPage.FirstConnecting)
        assertEquals("正在连接", connecting.title)
        assertEquals("已找到的热点", connecting.foundHeading)
        assertEquals("取消连接", connecting.secondary)
        assertEquals("是否取消连接", journeyModalTitle(JourneyPage.FirstConnecting, JourneyModal.CancelLink))
        assertEquals(connecting.copy(page = JourneyPage.SecondConnecting), journeyCopy(JourneyPage.SecondConnecting))
        assertEquals("是否取消连接", journeyModalTitle(JourneyPage.SecondConnecting, JourneyModal.CancelLink))

        val missed = journeyCopy(JourneyPage.FirstCantFind)
        assertEquals("找不到相机", missed.title)
        assertEquals(listOf("连接已超时一分钟"), missed.lines)
        assertEquals("请确认相机Wi-Fi已打开", missed.callout)
        assertEquals("继续连接", missed.primary)
        assertEquals("相机Wi-Fi已开启", journeyModalTitle(JourneyPage.FirstCantFind, JourneyModal.WifiOn))
        assertEquals(missed.copy(page = JourneyPage.SecondCantFind), journeyCopy(JourneyPage.SecondCantFind))
        assertEquals("相机Wi-Fi已开启", journeyModalTitle(JourneyPage.SecondCantFind, JourneyModal.WifiOn))

        val password = journeyCopy(JourneyPage.FirstPasswordError)
        assertEquals("Wi-Fi 密码错误", password.title)
        assertEquals("Wi-Fi 密码", password.placeholder)
        assertEquals("再次连接", password.primary)
        assertEquals(password.copy(page = JourneyPage.SecondPasswordError), journeyCopy(JourneyPage.SecondPasswordError))

        val auto = journeyCopy(JourneyPage.SecondAuto)
        assertEquals("正在自动连接", auto.title)
        assertEquals(listOf("请确认相机Wi-Fi已开启"), auto.lines)
        assertEquals("取消自动连接", auto.secondary)
        assertEquals("上次连接 NIKON_Z_30_12345", lastCameraLine("NIKON_Z_30_12345"))
        assertEquals("正在搜索相机", SEARCHING_LINE)
        assertEquals("确认取消自动连接？", journeyModalTitle(JourneyPage.SecondAuto, JourneyModal.CancelAuto))

        val back = journeyCopy(JourneyPage.SecondReturn)
        assertEquals("自动连接已取消", back.title)
        assertEquals(listOf("从下方选择要连接的相机，连接新相机"), back.lines)
        assertEquals("请输入新相机的密码", back.placeholder)
        assertEquals("连接相机", back.primary)
        assertEquals("已保存的相机连接", back.savedHeading)
        assertEquals("连接", back.savedAction)

        assertNull(journeyModalTitle(JourneyPage.Photos, JourneyModal.None))
        assertEquals(JourneyPage.entries.toSet(), JourneyPage.entries.map { journeyCopy(it).page }.toSet())
    }

    @Test
    fun listingPagesDoNotJoinFromAScan() {
        assertTrue(listsHotspots(JourneyPage.FirstHome))
        assertTrue(listsHotspots(JourneyPage.FirstCantFind))
        assertTrue(listsHotspots(JourneyPage.SecondReturn))
        assertTrue(listsHotspots(JourneyPage.SecondCantFind))
        assertFalse(listsHotspots(JourneyPage.FirstConnecting))
        assertFalse(listsHotspots(JourneyPage.SecondAuto))
    }

    @Test
    fun pickingAListedHotspotSelectsItWithoutJoining() {
        val home = firstHome().state.copy(seen = listOf("NIKON_Z_30_12345", "NIKON_Z_30_12346"))
        val picked = reduce(home, JourneyEvent.PickHotspot("nikon_z_30_12346"))
        assertEquals("NIKON_Z_30_12346", picked.state.targetSsid)
        assertEquals(JourneyPage.FirstHome, picked.state.page)
        assertEquals(emptyList(), picked.effects)
        val unknown = reduce(home, JourneyEvent.PickHotspot("Canon_R50"))
        assertEquals(null, unknown.state.targetSsid)
    }

    @Test
    fun scanningWhileAutoConnectingCountsAttempts() {
        val opened = reduce(initialJourney("NIKON_Z_30_12345", true), JourneyEvent.Opened(1_000)).state
        assertEquals(JourneyPage.SecondAuto, opened.page)
        assertEquals(0, opened.attempts)
        val first = reduce(opened, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), opened.generation)).state
        assertEquals(1, first.attempts)
        val second = reduce(first, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), first.generation)).state
        assertEquals(2, second.attempts)
    }

    @Test
    fun networkLostOnPhotosReconnectsTheSavedCamera() {
        val auto = secondAutoJoined()
        val leased = reduce(auto, JourneyEvent.NetworkAcquired(auto.generation)).state
        val photos = reduce(leased, JourneyEvent.PtpSessionOpened(leased.generation)).state
        assertEquals(JourneyPage.Photos, photos.page)
        val lost = reduce(photos, JourneyEvent.NetworkLost(photos.generation, 5_000))
        assertEquals(JourneyPage.SecondAuto, lost.state.page)
        assertTrue(lost.state.followSaved)
        assertEquals(65_000, lost.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), lost.effects)
        assertTrue(lost.state.disconnected)
    }

    @Test
    fun aLostMediaSessionGoesBackToTheAutoPageWithANotice() {
        val auto = secondAutoJoined()
        val leased = reduce(auto, JourneyEvent.NetworkAcquired(auto.generation)).state
        val photos = reduce(leased, JourneyEvent.PtpSessionOpened(leased.generation)).state
        val lost = reduce(photos, JourneyEvent.SessionLost(photos.generation, 5_000))
        assertEquals(JourneyPage.SecondAuto, lost.state.page)
        assertTrue(lost.state.disconnected)
        assertEquals(65_000, lost.state.deadlineAt)
        assertEquals(listOf(JourneyEffect.JOIN, JourneyEffect.SCAN), lost.effects)

        val missed = reduce(lost.state, JourneyEvent.Tick(lost.state.deadlineAt!!)).state
        val started = reduce(reduce(missed, JourneyEvent.ContinueConnect).state, JourneyEvent.Confirm(90_000)).state
        assertEquals(JourneyPage.SecondConnecting, started.page)
        assertFalse(started.disconnected)
    }

    @Test
    fun sessionLostOutsideThePhotosPageIsIgnored() {
        val auto = secondAutoJoined()
        val lost = reduce(auto, JourneyEvent.SessionLost(auto.generation, 5_000))
        assertEquals(auto, lost.state)
        assertEquals(emptyList(), lost.effects)
    }

    @Test
    fun aFailedJoinGoesBackToTheMatchingHomeWithoutDisconnectNotice() {
        val connecting = reduce(returnPage().state, JourneyEvent.ReconnectLast(3_000)).state
        assertEquals(JourneyPage.SecondConnecting, connecting.page)
        val back = reduce(connecting, JourneyEvent.JoinFailed(connecting.generation))
        assertEquals(JourneyPage.SecondReturn, back.state.page)
        assertEquals(listOf(JourneyEffect.RELEASE, JourneyEffect.SCAN), back.effects)
        assertFalse(back.state.disconnected)

        val first = connectingFirst()
        val home = reduce(first, JourneyEvent.JoinFailed(first.generation))
        assertEquals(JourneyPage.FirstHome, home.state.page)
    }
}

private fun firstHome() = reduce(initialJourney(null, false), JourneyEvent.Opened(1_000))

private fun askToConnect(state: JourneyState, password: String): JourneyTransition {
    val typed = reduce(state, JourneyEvent.PasswordChanged(password))
    return reduce(typed.state, JourneyEvent.ConnectCamera(1_000))
}

private fun connectingFirst(): JourneyState {
    val home = firstHome().state.copy(seen = listOf("NIKON_Z_30_12345"))
    val asked = askToConnect(home, "13579246")
    return reduce(asked.state, JourneyEvent.Confirm(1_000)).state
}

private fun secondAutoJoined(): JourneyState {
    val opened = reduce(initialJourney("NIKON_Z_30_12345", true), JourneyEvent.Opened(1_000))
    return reduce(opened.state, JourneyEvent.Scanned(listOf("NIKON_Z_30_12345"), opened.state.generation)).state
}

private fun returnPage(): JourneyTransition {
    val auto = secondAutoJoined()
    return reduce(reduce(auto, JourneyEvent.CancelAuto).state, JourneyEvent.Confirm(2_000))
}
