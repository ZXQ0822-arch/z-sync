package app.zsync.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkMachineTest {
    @Test
    fun firstVisitAsksForThePasswordShownOnTheCamera() {
        val state = initialLinkState(savedSsid = null, savedPassword = null)
        assertIs<LinkPhase.NeedPassword>(state.phase)

        val missed = reduce(state, LinkEvent.ScanResult(listOf("home")))
        assertIs<LinkPhase.WaitingForHotspot>(missed.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), missed.effects)

        val seen = reduce(state, LinkEvent.ScanResult(listOf("home", "Z_30")))
        assertEquals("Z_30", seen.state.targetSsid)
        assertIs<LinkPhase.NeedPassword>(seen.state.phase)
        assertTrue(seen.effects.isEmpty())

        val short = reduce(seen.state, LinkEvent.PasswordSubmitted("1234"))
        assertIs<LinkPhase.NeedPassword>(short.state.phase)
        assertTrue(short.effects.isEmpty())

        val typed = reduce(seen.state, LinkEvent.PasswordSubmitted("12345678"))
        assertIs<LinkPhase.Connecting>(typed.state.phase)
        assertTrue(typed.state.memory.hasSavedPassword)
        assertEquals(listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.JOIN), typed.effects)
    }

    @Test
    fun joinSavesThePasswordOnlyAfterTheNetworkIsUp() {
        val typed = LinkState(
            phase = LinkPhase.Connecting,
            targetSsid = "Z_30",
            pendingPassword = "12345678",
        )
        val prompted = reduce(typed, LinkEvent.SystemPromptVisible)
        assertIs<LinkPhase.AwaitingSystemAllow>(prompted.state.phase)

        val joined = reduce(prompted.state, LinkEvent.Joined)
        assertEquals(listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.CHECK_INTERNET), joined.effects)
        assertTrue(joined.state.memory.hasSavedPassword)
        assertNull(joined.state.pendingPassword)
        assertEquals("LinkState(phase=Connecting, saved=true, target=Z_30)", joined.state.toString())

        val online = reduce(joined.state, LinkEvent.Internet(available = true))
        val connected = assertIs<LinkPhase.Connected>(online.state.phase)
        assertTrue(connected.internetAvailable)

        val offline = reduce(joined.state, LinkEvent.Internet(available = false))
        assertFalse(assertIs<LinkPhase.Connected>(offline.state.phase).internetAvailable)
    }

    @Test
    fun aRememberedCameraJoinsWithoutAskingAgain() {
        val ready = initialLinkState(savedSsid = "Z_30", savedPassword = "12345678")
        assertIs<LinkPhase.Ready>(ready.phase)
        val scanned = reduce(ready, LinkEvent.ScanResult(listOf("\"Z_30\"")))
        assertEquals(listOf(LinkEffect.JOIN), scanned.effects)
        assertEquals(LinkPhase.Searching(0), scanned.state.phase)
    }

    @Test
    fun prefersTheSavedNameOverAnotherCamera() {
        assertEquals("MyCam", chooseSsid(listOf("Z_30", "MyCam"), savedName = "MyCam"))
    }

    @Test
    fun wrongPasswordIsForgottenAndAMissingHotspotStaysSeparate() {
        val joining = initialLinkState("Z_30", "12345678").copy(
            phase = LinkPhase.Connecting,
            targetSsid = "Z_30",
        )
        val wrong = reduce(joining, LinkEvent.JoinUnavailable(JoinRejection.WrongPassword))
        assertEquals(LinkPhase.Failed(LinkFailure.BadPassword), wrong.state.phase)
        assertFalse(wrong.state.memory.hasSavedPassword)
        assertEquals(listOf(LinkEffect.FORGET_PASSWORD), wrong.effects)
        assertIs<LinkPhase.NeedPassword>(reduce(wrong.state, LinkEvent.ConnectTapped).state.phase)
        val again = reduce(wrong.state, LinkEvent.PasswordSubmitted("87654321"))
        assertEquals(LinkPhase.Connecting, again.state.phase)
        assertEquals("87654321", again.state.pendingPassword)
        assertEquals(listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.JOIN), again.effects)
        val tooShort = reduce(wrong.state, LinkEvent.PasswordSubmitted("1234"))
        assertEquals(LinkPhase.NeedPassword, tooShort.state.phase)
        assertEquals(emptyList(), tooShort.effects)

        val gone = reduce(joining, LinkEvent.JoinUnavailable(JoinRejection.HotspotMissing))
        assertEquals(LinkPhase.Failed(LinkFailure.HotspotOff), gone.state.phase)
        assertTrue(gone.state.memory.hasSavedPassword)
        val look = reduce(gone.state, LinkEvent.ConnectTapped)
        assertEquals(LinkPhase.Searching(0), look.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), look.effects)

        val away = reduce(joining, LinkEvent.JoinUnavailable(JoinRejection.OffCamera))
        assertEquals(LinkPhase.Failed(LinkFailure.OffCamera), away.state.phase)
        assertTrue(away.state.memory.hasSavedPassword)
        assertEquals(emptyList(), away.effects)
        val back = reduce(away.state, LinkEvent.ConnectTapped)
        assertEquals(LinkPhase.Searching(0), back.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), back.effects)

        val timed = reduce(joining, LinkEvent.JoinUnavailable(JoinRejection.TimedOut))
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped), timed.state.phase)
        assertTrue(timed.state.memory.hasSavedPassword)

        val searching = reduce(initialLinkState(null, "12345678"), LinkEvent.ScreenOpened).state
        val counted = reduce(searching, LinkEvent.ScanResult(emptyList())).state
        assertEquals(LinkPhase.Searching(1), counted.phase)
        val tapped = reduce(counted, LinkEvent.ConnectTapped)
        assertEquals(LinkPhase.Searching(1), tapped.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), tapped.effects)
    }

    @Test
    fun openingTheScreenAutoJoinsAVisibleCameraWithTheSavedPassword() {
        val saved = initialLinkState(savedSsid = null, savedPassword = "camera-pass")
        val opened = reduce(saved, LinkEvent.ScreenOpened)
        assertEquals(listOf(LinkEffect.SCAN), opened.effects)

        val found = reduce(opened.state, LinkEvent.ScanResult(listOf("HOME_NET_01", "NIKON_Z_30_12345")))
        assertEquals(LinkPhase.Searching(0), found.state.phase)
        assertEquals("NIKON_Z_30_12345", found.state.targetSsid)
        assertEquals(listOf(LinkEffect.JOIN), found.effects)
    }

    @Test
    fun aChosenHotspotStaysTheTargetWhenAnotherCameraIsAlsoVisible() {
        val saved = reduce(initialLinkState("NIKON_Z_30_12345", "camera-pass"), LinkEvent.ScreenOpened).state
        val chosen = reduce(saved, LinkEvent.TargetChosen("NIKON_Z_30_12346"))
        assertEquals("NIKON_Z_30_12346", chosen.state.targetSsid)
        assertEquals(emptyList(), chosen.effects)
        val seen = reduce(chosen.state, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345", "NIKON_Z_30_12346")))
        assertEquals("NIKON_Z_30_12346", seen.state.targetSsid)
        assertEquals(listOf(LinkEffect.JOIN), seen.effects)
        val ignored = reduce(saved, LinkEvent.TargetChosen("home-wifi"))
        assertEquals(saved.targetSsid, ignored.state.targetSsid)
    }

    @Test
    fun openingTheScreenKeepsThePasswordFieldWhenTheCameraIsAbsentOrNoPasswordIsSaved() {
        val saved = reduce(initialLinkState(null, "camera-pass"), LinkEvent.ScreenOpened).state
        val absent = reduce(saved, LinkEvent.ScanResult(listOf("HOME_NET_01")))
        assertEquals(LinkPhase.Searching(1), absent.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), absent.effects)
        var misses = absent.state
        repeat(4) { misses = reduce(misses, LinkEvent.ScanResult(listOf("HOME_NET_01"))).state }
        assertEquals(LinkPhase.Searching(5), misses.phase)

        val fresh = reduce(initialLinkState(null, null), LinkEvent.ScreenOpened).state
        val visible = reduce(fresh, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345")))
        assertEquals(LinkPhase.NeedPassword, visible.state.phase)
        assertEquals(emptyList(), visible.effects)
    }

    @Test
    fun connectDuringTheSystemPromptDoesNotStartAnotherJoin() {
        val waiting = LinkState(
            phase = LinkPhase.AwaitingSystemAllow,
            memory = LinkMemory(hasSavedPassword = true),
            targetSsid = "NIKON_Z_30_12345",
        )
        val again = reduce(waiting, LinkEvent.ConnectTapped)
        assertEquals(waiting, again.state)
        assertEquals(emptyList(), again.effects)

        val connecting = waiting.copy(phase = LinkPhase.Connecting)
        val during = reduce(connecting, LinkEvent.ConnectTapped)
        assertEquals(connecting, during.state)
        assertEquals(emptyList(), during.effects)
    }

    @Test
    fun aDroppedCameraNetworkReconnectsThreeTimesThenAsks() {
        var state = LinkState(
            phase = LinkPhase.Connected("Z 30", internetAvailable = false),
            memory = LinkMemory(hasSavedPassword = true),
        )
        repeat(3) { index ->
            val again = reduce(state, LinkEvent.NetworkLost)
            assertEquals(LinkPhase.Searching(0), again.state.phase)
            assertEquals(listOf(LinkEffect.SCAN), again.effects)
            assertEquals(index + 1, again.state.reconnects)
            state = again.state.copy(phase = LinkPhase.Connecting)
        }
        val givenUp = reduce(state, LinkEvent.NetworkLost)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped), givenUp.state.phase)
        assertEquals(0, givenUp.state.reconnects)
        val manual = reduce(givenUp.state, LinkEvent.ConnectTapped)
        assertEquals(LinkPhase.Searching(0), manual.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), manual.effects)
        assertEquals(0, manual.state.reconnects)

        val away = reduce(
            LinkState(phase = LinkPhase.Connected("Z 30", internetAvailable = false), memory = LinkMemory(hasSavedPassword = true)),
            LinkEvent.NetworkLost,
        )
        val stillAway = reduce(away.state, LinkEvent.NetworkLost)
        assertEquals(1, stillAway.state.reconnects)
        assertEquals(listOf(LinkEffect.SCAN), stillAway.effects)

        val idle = initialLinkState(null, null)
        assertEquals(idle, reduce(idle, LinkEvent.NetworkLost).state)
    }

    @Test
    fun retryUsesTheTypedPasswordInsteadOfAskingAgain() {
        val typed = reduce(initialLinkState(null, null), LinkEvent.PasswordSubmitted("13579246")).state
        val off = reduce(typed, LinkEvent.JoinUnavailable(JoinRejection.HotspotMissing)).state
        assertEquals(LinkPhase.Failed(LinkFailure.HotspotOff), off.phase)
        val retry = reduce(off, LinkEvent.ConnectTapped)
        assertIs<LinkPhase.Connecting>(retry.state.phase)
        assertEquals(listOf(LinkEffect.JOIN), retry.effects)
        assertEquals("13579246", retry.state.pendingPassword)

        val refused = reduce(typed, LinkEvent.JoinError("SecurityException: denied")).state
        assertEquals(LinkPhase.Failed(LinkFailure.Unknown, "SecurityException: denied"), refused.phase)
        assertEquals(listOf(LinkEffect.JOIN), reduce(refused, LinkEvent.ConnectTapped).effects)
    }

    @Test
    fun joinsThroughTheSystemWithoutWaitingForAScan() {
        val fresh = initialLinkState(savedSsid = null, savedPassword = null)
        val typed = reduce(fresh, LinkEvent.PasswordSubmitted("12345678"))
        assertNull(typed.state.targetSsid)
        assertEquals(listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.JOIN), typed.effects)

        val online = reduce(reduce(typed.state, LinkEvent.Joined).state, LinkEvent.Internet(available = true))
        assertIs<LinkPhase.Connected>(online.state.phase)

        val ready = initialLinkState(savedSsid = null, savedPassword = "12345678")
        val looking = reduce(ready, LinkEvent.ConnectTapped)
        assertEquals(listOf(LinkEffect.SCAN), looking.effects)
        assertEquals(LinkPhase.Searching(0), looking.state.phase)
        assertEquals(
            listOf(LinkEffect.JOIN),
            reduce(looking.state, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345"))).effects,
        )
        val stayed = reduce(online.state, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345")))
        assertEquals(online.state, stayed.state)
        assertEquals(emptyList(), stayed.effects)
        assertIs<LinkPhase.NeedPassword>(reduce(fresh, LinkEvent.ConnectTapped).state.phase)
    }

    @Test
    fun goingBackKeepsThePasswordAndDoesNotJoinAgain() {
        val typed = reduce(initialLinkState(null, null), LinkEvent.PasswordSubmitted("13579246")).state
        val prompted = reduce(typed, LinkEvent.SystemPromptVisible).state
        val back = reduce(prompted, LinkEvent.Disconnect)
        assertIs<LinkPhase.Ready>(back.state.phase)
        assertEquals(null, back.state.pendingPassword)
        assertTrue(back.state.memory.hasSavedPassword)
        assertEquals(listOf(LinkEffect.SAVE_PASSWORD), back.effects)

        val saved = reduce(
            initialLinkState("Z_30", "12345678").copy(phase = LinkPhase.Searching(1), targetSsid = "Z_30"),
            LinkEvent.Disconnect,
        )
        assertIs<LinkPhase.Ready>(saved.state.phase)
        assertEquals(emptyList(), saved.effects)
        assertTrue(saved.state.memory.hasSavedPassword)
        assertEquals(null, saved.state.targetSsid)
    }

    @Test
    fun reopeningUsesThePasswordSavedWhenItWasTyped() {
        val typed = reduce(initialLinkState(null, null), LinkEvent.PasswordSubmitted("13579246"))
        assertTrue(typed.state.memory.hasSavedPassword)
        assertEquals(listOf(LinkEffect.SAVE_PASSWORD, LinkEffect.JOIN), typed.effects)

        val reopened = reduce(initialLinkState("NIKON_Z_30_12345", "13579246"), LinkEvent.ScreenOpened)
        assertEquals(LinkPhase.Searching(0), reopened.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), reopened.effects)

        val rejected = reduce(typed.state, LinkEvent.JoinUnavailable(JoinRejection.WrongPassword))
        assertFalse(rejected.state.memory.hasSavedPassword)
        assertEquals(listOf(LinkEffect.FORGET_PASSWORD), rejected.effects)
        assertIs<LinkPhase.NeedPassword>(initialLinkState("NIKON_Z_30_12345", null).phase)
    }

    @Test
    fun anotherClientAndDisconnectKeepTheSavedPassword() {
        val connected = LinkState(
            phase = LinkPhase.Connected("Z_30", internetAvailable = true),
            memory = LinkMemory(savedSsid = "Z_30", hasSavedPassword = true),
            targetSsid = "Z_30",
        )
        assertEquals(LinkPhase.Failed(LinkFailure.ClientBusy), reduce(connected, LinkEvent.CameraBusy).state.phase)
        val left = reduce(connected, LinkEvent.Disconnect)
        assertIs<LinkPhase.Ready>(left.state.phase)
        assertTrue(left.state.memory.hasSavedPassword)
    }

    @Test
    fun aSavedCameraUsesReconnectUntilANewPasswordIsTyped() {
        assertEquals("重新连接", homeActionLabel(passwordSaved = true, typed = ""))
        assertEquals("连接", homeActionLabel(passwordSaved = true, typed = "13579246"))
        assertEquals("连接", homeActionLabel(passwordSaved = false, typed = ""))
    }

    @Test
    fun theFirstScanAlreadyExplainsThatTheCameraMayBeOff() {
        assertEquals("正在扫描", scanCountLabel(0))
        assertEquals("扫描一次", scanCountLabel(1))
        assertEquals("扫描两次", scanCountLabel(2))
        assertEquals("扫描六次", scanCountLabel(6))
        assertEquals("扫描12次", scanCountLabel(12))
        assertEquals("相机可能没开机或不在旁边，请打开相机的 Wi-Fi，连接后再试。", SCAN_HINT)
    }

    @Test
    fun waitingForTheSystemShowsASpinnerAndNoAllowButton() {
        assertTrue(showsWaitSpinner(LinkPhase.AwaitingSystemAllow))
        assertTrue(showsWaitSpinner(LinkPhase.Connecting))
        assertFalse(showsWaitSpinner(LinkPhase.NeedPassword))
        assertFalse(showsSystemAllowButton(LinkPhase.AwaitingSystemAllow))
    }

    @Test
    fun theCantFindPagesKeepScanningAndDoNotJoin() {
        val waiting = LinkState(
            phase = LinkPhase.WaitingForHotspot,
            memory = LinkMemory(savedSsid = "NIKON_Z_30_12345", hasSavedPassword = true),
        )
        val seen = reduce(waiting, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345")))
        assertEquals(LinkPhase.WaitingForHotspot, seen.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), seen.effects)
        val missed = reduce(waiting, LinkEvent.ScanResult(emptyList()))
        assertEquals(LinkPhase.WaitingForHotspot, missed.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), missed.effects)

        val off = waiting.copy(phase = LinkPhase.Failed(LinkFailure.HotspotOff))
        val stillOff = reduce(off, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345")))
        assertEquals(LinkPhase.Failed(LinkFailure.HotspotOff), stillOff.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), stillOff.effects)

        val dropped = waiting.copy(phase = LinkPhase.Failed(LinkFailure.Dropped))
        val stillDropped = reduce(dropped, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345")))
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped), stillDropped.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), stillDropped.effects)

        val fresh = LinkState(phase = LinkPhase.WaitingForHotspot)
        val ask = reduce(fresh, LinkEvent.ScanResult(listOf("NIKON_Z_30_12345")))
        assertEquals(LinkPhase.NeedPassword, ask.state.phase)
        assertEquals(emptyList(), ask.effects)

        val retry = reduce(off, LinkEvent.ConnectTapped)
        assertEquals(LinkPhase.Searching(0), retry.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), retry.effects)
    }

    @Test
    fun anOpenAttemptExpiresIntoCantFindAndKeepsScanning() {
        assertEquals(60_000L, CONNECT_LIMIT_MS)
        val saved = LinkMemory(savedSsid = "NIKON_Z_30_12345", hasSavedPassword = true)
        val searching = LinkState(phase = LinkPhase.Searching(2), memory = saved, targetSsid = "NIKON_Z_30_12345")
        val expired = reduce(searching, LinkEvent.AttemptExpired)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped), expired.state.phase)
        assertEquals(listOf(LinkEffect.SCAN), expired.effects)
        assertTrue(expired.state.memory.hasSavedPassword)

        val connecting = searching.copy(phase = LinkPhase.Connecting)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped), reduce(connecting, LinkEvent.AttemptExpired).state.phase)
        val waiting = searching.copy(phase = LinkPhase.AwaitingSystemAllow)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped), reduce(waiting, LinkEvent.AttemptExpired).state.phase)

        val home = searching.copy(phase = LinkPhase.Ready)
        assertEquals(home, reduce(home, LinkEvent.AttemptExpired).state)
        val photos = searching.copy(phase = LinkPhase.Connected("NIKON_Z_30_12345", false))
        assertEquals(photos.phase, reduce(photos, LinkEvent.AttemptExpired).state.phase)
        val missed = searching.copy(phase = LinkPhase.WaitingForHotspot)
        assertEquals(missed.phase, reduce(missed, LinkEvent.AttemptExpired).state.phase)

        val late = reduce(expired.state, LinkEvent.Joined)
        assertEquals(expired.state, late.state)
        assertEquals(emptyList(), late.effects)
        assertEquals(expired.state, reduce(expired.state, LinkEvent.SystemPromptVisible).state)
        assertEquals(expired.state.phase, reduce(expired.state, LinkEvent.Internet(true)).state.phase)
    }

    @Test
    fun aFailedAttemptReleasesTheConnectCooldown() {
        assertTrue(releasesConnectCooldown(LinkPhase.Failed(LinkFailure.BadPassword)))
        assertTrue(releasesConnectCooldown(LinkPhase.Failed(LinkFailure.Dropped)))
        assertFalse(releasesConnectCooldown(LinkPhase.Connecting))
        assertFalse(releasesConnectCooldown(LinkPhase.NeedPassword))
    }
}
