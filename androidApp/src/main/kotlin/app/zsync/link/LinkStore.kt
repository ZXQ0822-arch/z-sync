package app.zsync.link

import app.zsync.log.QuickLog
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.zsync.model.guidToHex
import app.zsync.model.hexToGuid
import app.zsync.model.newInitiatorGuid

interface LinkStore {
    val ssid: String?
    val password: String?

    fun save(ssid: String?, password: String)

    fun clearPassword()

    fun guid(): ByteArray
}

class EncryptedLinkStore(context: Context) : LinkStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override val ssid: String?
        get() = prefs.getString(KEY_SSID, null)

    override val password: String?
        get() = prefs.getString(KEY_PASSWORD, null)

    override fun save(ssid: String?, password: String) {
        val edit = prefs.edit().putString(KEY_PASSWORD, password)
        if (ssid != null) edit.putString(KEY_SSID, ssid)
        edit.apply()
        QuickLog.i(LOG_TAG, "remembered ssid=${ssid ?: "pattern"}")
    }

    override fun clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply()
        QuickLog.i(LOG_TAG, "forgot password")
    }

    override fun guid(): ByteArray {
        val existing = prefs.getString(KEY_GUID, null)
        if (existing != null) return hexToGuid(existing)
        val created = newInitiatorGuid()
        prefs.edit().putString(KEY_GUID, guidToHex(created)).apply()
        return created
    }

    private companion object {
        const val FILE = "z-sync-link"
        const val KEY_SSID = "ssid"
        const val KEY_PASSWORD = "password"
        const val KEY_GUID = "guid"
    }
}
