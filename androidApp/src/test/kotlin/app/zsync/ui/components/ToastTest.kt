package app.zsync.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zsync.gallery.PREVIEWS_LOADING_NOTICE
import app.zsync.ui.theme.ZSyncTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToastTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun itShowsTheMessageItIsGiven() {
        rule.setContent {
            ZSyncTheme {
                ZSyncToast(PREVIEWS_LOADING_NOTICE)
            }
        }
        rule.onNodeWithText(PREVIEWS_LOADING_NOTICE).assertIsDisplayed()
    }

    @Test
    fun tappingDismissesWhenTheCallerWantsThat() {
        var dismissed = 0
        rule.setContent {
            ZSyncTheme {
                ZSyncToast("缩略图仍在加载", onDismiss = { dismissed += 1 }, modifier = Modifier.testTag("toast"))
            }
        }
        rule.onNodeWithTag("toast").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aToastWithoutADismissHandlerIsNotClickable() {
        rule.setContent {
            ZSyncTheme {
                ZSyncToast("已保存 2 张", modifier = Modifier.testTag("toast"))
            }
        }
        rule.onNodeWithTag("toast").assertIsDisplayed()
        rule.onNodeWithTag("toast").performClick()
        // No dismiss handler means no click action; the click must be a no-op rather than a crash.
        rule.onNodeWithTag("toast").assertIsDisplayed()
    }
}
