package app.zsync.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zsync.ui.theme.ZSyncTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatteryHintCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theCardSaysWhatBreaksAndOffersTheAllowlist() {
        rule.setContent {
            ZSyncTheme { BatteryHintCard(onAllow = {}, onDismiss = {}) }
        }

        rule.onNodeWithText("切到别的应用时连接会断开").assertIsDisplayed()
        rule.onNodeWithText("去设置").assertIsDisplayed()
        rule.onNodeWithText("以后再说").assertIsDisplayed()
    }

    @Test
    fun eachButtonReportsItsOwnTap() {
        var allowed = 0
        var dismissed = 0
        rule.setContent {
            ZSyncTheme {
                BatteryHintCard(onAllow = { allowed += 1 }, onDismiss = { dismissed += 1 })
            }
        }

        rule.onNodeWithText("去设置").performClick()
        assertEquals(1, allowed)
        assertEquals(0, dismissed)

        rule.onNodeWithText("以后再说").performClick()
        assertEquals(1, allowed)
        assertEquals(1, dismissed)
    }
}
