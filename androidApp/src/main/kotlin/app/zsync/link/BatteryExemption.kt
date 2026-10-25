package app.zsync.link

import app.zsync.log.QuickLog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.zsync.BuildConfig
import java.util.Locale

/**
 * Keeps the app out of the ROM's background freezer.
 *
 * A foreground service is not enough on ColorOS: its `StrictMode` scene froze the connected app and
 * `netd` destroyed the PTP sockets in the same millisecond, so the camera saw the session end and
 * turned its own Wi-Fi off. See `docs/solution/coloros-background-drop.md`. The one signal those
 * freezers honour is the system's battery-optimisation allowlist, so the app asks to be put on it.
 *
 * The package name is taken from [BuildConfig.APPLICATION_ID], never from the context: the first
 * build asked `context.packageName` and, at this call site, it came back empty, so the system opened
 * the allowlist dialog with `dat=package:` and nothing could actually be granted.
 */
object BatteryExemption {
    /**
     * True on ColorOS/Oplus ROMs.
     *
     * There the battery-optimisation allowlist does not stop the background freezer: `0.1.31` is
     * on the Doze allowlist (`mDeviceIdleWhitelist` contains its uid) and is still frozen by
     * `OplusHansManager`, whose own per-app "允许后台运行" toggle is the only thing that might. That
     * toggle lives in the app's system settings page, so the card must point there instead.
     */
    fun isOplusRom(): Boolean {
        val maker = Build.MANUFACTURER.lowercase(Locale.ROOT)
        return maker.contains("oppo") || maker.contains("oneplus") || maker.contains("realme")
    }

    /** True once the app is on the allowlist and may run in the background. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID)
    }

    /** The intent behind [request]; exposed so the package can be asserted in a test. */
    internal fun allowlistIntent(packageName: String = BuildConfig.APPLICATION_ID): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the system dialog that puts the app on the allowlist. */
    fun request(context: Context) {
        QuickLog.i(
            LOG_TAG,
            "battery request package=${BuildConfig.APPLICATION_ID} contextPackage=${context.packageName}",
        )
        try {
            context.startActivity(allowlistIntent())
            QuickLog.i(LOG_TAG, "asked for the battery allowlist")
        } catch (missing: RuntimeException) {
            // Some ROMs hide the dialog; the app settings page carries the same toggle.
            QuickLog.e(LOG_TAG, "battery dialog missing ${missing.javaClass.simpleName}")
            openAppSettings(context)
        }
    }

    /** Fallback for ROMs that hide the dialog: the app's own settings page. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", BuildConfig.APPLICATION_ID, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (missing: RuntimeException) {
            QuickLog.e(LOG_TAG, "app settings missing ${missing.javaClass.simpleName}")
        }
    }
}
