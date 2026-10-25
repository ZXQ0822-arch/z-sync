package app.zsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraSsidTest {
    @Test
    fun matchesCameraStyleNames() {
        assertTrue(isCameraSsid("Z_30"))
        assertTrue(isCameraSsid("\"Nikon_Z30_1234\""))
        assertTrue(isCameraSsid("z-30"))
        assertFalse(isCameraSsid("home-wifi"))
    }

    @Test
    fun matchesTheSavedName() {
        assertTrue(isCameraSsid("anything", savedName = "anything"))
        assertFalse(isCameraSsid("anything", savedName = "other"))
    }

    @Test
    fun aScanInsideTheFreshnessGateIsAccepted() {
        assertTrue(isScanFresh(timestampUs = 10_000_000, nowUs = 10_000_000, maxAgeMs = 30_000))
        assertTrue(isScanFresh(timestampUs = 10_000_000, nowUs = 10_000_000 + 29_000 * 1_000, maxAgeMs = 30_000))
    }

    @Test
    fun aScanOlderThanTheFreshnessGateIsRejected() {
        assertFalse(isScanFresh(timestampUs = 10_000_000, nowUs = 10_000_000 + 30_001 * 1_000, maxAgeMs = 30_000))
        assertFalse(isScanFresh(timestampUs = 0, nowUs = 5_000_000, maxAgeMs = 1_000))
    }

    @Test
    fun aNullFreshnessGateAcceptsAnyAge() {
        assertTrue(isScanFresh(timestampUs = 0, nowUs = Long.MAX_VALUE, maxAgeMs = null))
    }

    @Test
    fun onlyTheTargetNetworkCanBeAWrongPassword() {
        assertTrue(
            wrongPasswordForTarget(
                authFailed = true,
                seenSsid = "NIKON_Z_30_12345",
                seenBssid = "aa:bb:cc:dd:ee:ff",
                targetSsid = "NIKON_Z_30_12345",
                targetBssid = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertFalse(
            wrongPasswordForTarget(
                authFailed = true,
                seenSsid = "home-wifi",
                seenBssid = "11:22:33:44:55:66",
                targetSsid = "NIKON_Z_30_12345",
                targetBssid = "aa:bb:cc:dd:ee:ff",
            ),
        )
        assertFalse(
            wrongPasswordForTarget(
                authFailed = false,
                seenSsid = "NIKON_Z_30_12345",
                seenBssid = "aa:bb:cc:dd:ee:ff",
                targetSsid = "NIKON_Z_30_12345",
                targetBssid = "aa:bb:cc:dd:ee:ff",
            ),
        )
        assertTrue(
            wrongPasswordForTarget(
                authFailed = true,
                seenSsid = "<unknown ssid>",
                seenBssid = "<unknown ssid>",
                targetSsid = "NIKON_Z_30_12345",
                targetBssid = "aa:bb:cc:dd:ee:ff",
            ),
        )
        assertTrue(
            wrongPasswordForTarget(
                authFailed = true,
                seenSsid = null,
                seenBssid = null,
                targetSsid = "NIKON_Z_30_12345",
                targetBssid = "aa:bb:cc:dd:ee:ff",
            ),
        )
    }

    @Test
    fun passwordLengthMatchesWpa2() {
        assertFalse(isAcceptableHotspotPassword("short"))
        assertTrue(isAcceptableHotspotPassword("12345678"))
        assertFalse(isAcceptableHotspotPassword("x".repeat(64)))
    }
}
