package app.zsync.link

import android.content.Intent
import android.provider.Settings
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatteryExemptionTest {
    @Test
    fun theAllowlistIntentCarriesTheRealPackageName() {
        val intent = BatteryExemption.allowlistIntent("app.zsync")

        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:app.zsync", intent.dataString)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun thePackageNameNeverComesBackEmpty() {
        // The first build read context.packageName, which returned "" here and opened the dialog
        // with `dat=package:`. The constant must never be empty.
        assertEquals("app.zsync", app.zsync.BuildConfig.APPLICATION_ID)
    }
}
