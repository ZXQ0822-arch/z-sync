package app.zsync.ui.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zsync.session.JourneyModal
import app.zsync.session.JourneyPage
import app.zsync.session.JourneyState
import app.zsync.session.designFile
import app.zsync.session.journeyChrome
import app.zsync.ui.theme.ZSyncTheme
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JourneyScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun eachDesignPageShowsItsOwnTitle() {
        val pages = listOf(
            JourneyPage.FirstHome,
            JourneyPage.FirstConnecting,
            JourneyPage.FirstCantFind,
            JourneyPage.FirstPasswordError,
            JourneyPage.SecondAuto,
            JourneyPage.SecondReturn,
            JourneyPage.SecondConnecting,
            JourneyPage.SecondCantFind,
            JourneyPage.SecondPasswordError,
        ).map { page ->
            JourneyState(
                page = page,
                savedSsid = "NIKON_Z_30_12345",
                hasSavedPassword = true,
                typedPassword = "13579246",
            )
        }
        var index by mutableStateOf(0)
        rule.setContent {
            ZSyncTheme {
                JourneyScreen(
                    state = pages[index],
                    version = "v0.1.20 (20)",
                    onPassword = {},
                    onPrimary = {},
                    onSecondary = {},
                    onSaved = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        pages.forEachIndexed { i, state ->
            rule.runOnIdle { index = i }
            rule.onNodeWithTag(designFile(state.page)).assertIsDisplayed()
            assertTrue(rule.onAllNodesWithText(journeyChrome(state).title).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun theReturnPageSaysItIsSearchingForTheCameraWhenNothingIsListed() {
        rule.setContent {
            ZSyncTheme {
                JourneyScreen(
                    state = JourneyState(
                        page = JourneyPage.SecondReturn,
                        savedSsid = "NIKON_Z_30_12345",
                        hasSavedPassword = true,
                        typedPassword = "13579246",
                        seen = emptyList(),
                    ),
                    version = "v0.1.27 (27)",
                    onPassword = {},
                    onPrimary = {},
                    onSecondary = {},
                    onSaved = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("正在搜索相机").assertIsDisplayed()
    }

    @Test
    fun theDialogPutsCancelOnTheLeftAndConfirmOnTheRight() {
        var dismissed = false
        var confirmed = false
        rule.setContent {
            ZSyncTheme {
                JourneyScreen(
                    state = JourneyState(
                        page = JourneyPage.FirstHome,
                        modal = JourneyModal.WifiOn,
                        typedPassword = "13579246",
                    ),
                    version = "v0.1.20 (20)",
                    onPassword = {},
                    onPrimary = {},
                    onSecondary = {},
                    onSaved = {},
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }
        rule.onNodeWithTag("confirm-scrim").assertIsDisplayed()
        rule.onNodeWithText("相机 Wi-Fi 已开启？").assertIsDisplayed()
        val left = rule.onNodeWithTag("confirm-left").fetchSemanticsNode().boundsInRoot.left
        val right = rule.onNodeWithTag("confirm-right").fetchSemanticsNode().boundsInRoot.left
        assertTrue(left < right)
        rule.onNodeWithTag("confirm-left").performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
    }
}
