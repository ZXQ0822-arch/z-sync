package app.zsync.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zsync.ui.theme.ZSyncTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag

/**
 * The buttons are shared by every screen, so these tests pin the contract each call site relies on:
 * the label is shown, an enabled button fires, and a disabled one does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ButtonsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aStoneButtonShowsItsLabelAndFiresWhenEnabled() {
        var clicks = 0
        rule.setContent {
            ZSyncTheme {
                StoneButton("连接相机", onClick = { clicks += 1 }, modifier = Modifier.testTag("stone"))
            }
        }
        rule.onNodeWithText("连接相机").assertIsDisplayed()
        rule.onNodeWithTag("stone").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun aDisabledButtonNeverFires() {
        var stone = 0
        var cancel = 0
        var danger = 0
        rule.setContent {
            ZSyncTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StoneButton("主", enabled = false, onClick = { stone += 1 }, modifier = Modifier.testTag("stone"))
                    CancelButton("次", enabled = false, onClick = { cancel += 1 }, modifier = Modifier.testTag("ghost"))
                    DangerButton("删", enabled = false, onClick = { danger += 1 }, modifier = Modifier.testTag("danger"))
                }
            }
        }
        rule.onNodeWithTag("stone").performClick()
        rule.onNodeWithTag("ghost").performClick()
        rule.onNodeWithTag("danger").performClick()
        assertEquals(0, stone)
        assertEquals(0, cancel)
        assertEquals(0, danger)
    }

    @Test
    fun secondaryAndDestructiveButtonsFireAndKeepTheirOwnLabel() {
        var cancel = 0
        var danger = 0
        rule.setContent {
            ZSyncTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CancelButton("取消", onClick = { cancel += 1 }, modifier = Modifier.testTag("ghost"))
                    DangerButton("删除", onClick = { danger += 1 }, modifier = Modifier.testTag("danger"))
                }
            }
        }
        rule.onNodeWithText("取消").assertIsDisplayed()
        rule.onNodeWithText("删除").assertIsDisplayed()
        rule.onNodeWithTag("ghost").performClick()
        rule.onNodeWithTag("danger").performClick()
        assertEquals(1, cancel)
        assertEquals(1, danger)
    }
}
