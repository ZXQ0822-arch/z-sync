package app.zsync.model

fun isCameraSsid(ssid: String, savedName: String? = null): Boolean {
    val name = ssid.trim().removeSurrounding("\"")
    if (!savedName.isNullOrBlank() && name.equals(savedName.trim(), ignoreCase = true)) {
        return true
    }
    val compact = name.lowercase().replace("_", "").replace("-", "").replace(" ", "")
    return compact.contains("z30")
}

fun isAcceptableHotspotPassword(password: String): Boolean = password.length in 8..63

/**
 * True when a Wi-Fi scan result recorded at [timestampUs] (microseconds since boot) is still
 * trustworthy given the current time [nowUs]. A null [maxAgeMs] accepts anything; otherwise the
 * result must not be older than [maxAgeMs]. Used to stop stale cached scan results from being
 * treated as a live camera hotspot.
 */
fun isScanFresh(timestampUs: Long, nowUs: Long, maxAgeMs: Long?): Boolean =
    maxAgeMs == null || nowUs - timestampUs <= maxAgeMs * 1_000

fun wrongPasswordForTarget(
    authFailed: Boolean,
    seenSsid: String?,
    seenBssid: String?,
    targetSsid: String?,
    targetBssid: String?,
): Boolean {
    if (!authFailed) return false
    val targetName = cleanNetworkName(targetSsid) ?: return false
    val seenName = cleanNetworkName(seenSsid)
    // The system reported an authentication failure during our join. When it does not say which
    // SSID it was for (seen is unknown or "<unknown ssid>"), the attempt was for the target we are
    // joining, so the target's password is the one that failed.
    if (seenName != null && !seenName.equals(targetName, ignoreCase = true)) return false
    val seenMac = cleanNetworkName(seenBssid)
    val targetMac = cleanNetworkName(targetBssid)
    return seenName == null || seenMac == null || targetMac == null || seenMac.equals(targetMac, ignoreCase = true)
}

private fun cleanNetworkName(value: String?): String? {
    val name = value?.trim()?.removeSurrounding("\"") ?: return null
    if (name.isBlank() || name.equals("<unknown ssid>", ignoreCase = true)) return null
    return name
}
