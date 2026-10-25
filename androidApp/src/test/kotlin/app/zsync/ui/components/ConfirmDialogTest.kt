package app.zsync.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.zsync.ui.theme.ZSyncTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both delete entry points and the connect dialogs use this one component, so these tests pin the
 * contract they all rely on: only the right button acts, the left one just closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfirmDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun itShowsTheTitleAndBothButtonLabels() {
        rule.setContent {
            ZSyncTheme {
                ConfirmDialog(
                    title = "从手机相册删除这张照片？",
                    confirmLabel = "删除",
                    destructive = true,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("confirm-scrim").assertIsDisplayed()
        rule.onNodeWithText("从手机相册删除这张照片？").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
        rule.onNodeWithText("删除").assertIsDisplayed()
    }

    @Test
    fun onlyTheRightButtonConfirms() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            ZSyncTheme {
                ConfirmDialog(
                    title = "从相机删除这张照片？",
                    confirmLabel = "删除",
                    destructive = true,
                    onConfirm = { confirmed += 1 },
                    onDismiss = { dismissed += 1 },
                )
            }
        }
        rule.onNodeWithTag("confirm-left").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)

        rule.onNodeWithTag("confirm-right").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed, "confirming must not also dismiss")
    }

    @Test
    fun theLeftButtonStaysLeftOfTheRightOne() {
        rule.setContent {
            ZSyncTheme {
                ConfirmDialog(
                    title = "从相机删除这 2 张照片？",
                    confirmLabel = "删除",
                    destructive = true,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        val left = rule.onNodeWithTag("confirm-left").fetchSemanticsNode().boundsInRoot.left
        val right = rule.onNodeWithTag("confirm-right").fetchSemanticsNode().boundsInRoot.left
        assertTrue(left < right)
    }

    @Test
    fun aNonDestructiveDialogStillConfirmsOnTheRight() {
        var confirmed = false
        rule.setContent {
            ZSyncTheme {
                ConfirmDialog(
                    title = "相机 Wi-Fi 已开启？",
                    confirmLabel = "确认",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("确认").assertIsDisplayed()
        rule.onNodeWithTag("confirm-right").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun theDismissLabelDefaultsToCancel() {
        var dismissed = false
        rule.setContent {
            ZSyncTheme {
                ConfirmDialog(
                    title = "从相机删除这张照片？",
                    confirmLabel = "删除",
                    destructive = true,
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        rule.onNodeWithText("取消").assertIsDisplayed()
        rule.onNodeWithTag("confirm-left").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun tappingTheScrimDismissesButTappingTheCardDoesNot() {
        var dismissed = 0
        rule.setContent {
            ZSyncTheme {
                ConfirmDialog(
                    title = "从相机删除这张照片？",
                    confirmLabel = "删除",
                    destructive = true,
                    onConfirm = {},
                    onDismiss = { dismissed += 1 },
                )
            }
        }
        // Tapping the card itself (its title area) must not dismiss.
        rule.onNodeWithTag("confirm-dialog").performClick()
        rule.runOnIdle { assertEquals(0, dismissed) }
        // Tapping the scrim outside the card dismisses, as the design language requires.
        rule.onNodeWithTag("confirm-scrim").performTouchInput { click(Offset(16f, 16f)) }
        rule.runOnIdle { assertEquals(1, dismissed) }
    }
}
