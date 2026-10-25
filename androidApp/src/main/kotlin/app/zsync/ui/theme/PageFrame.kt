package app.zsync.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one page frame. Every screen uses these instead of writing its own numbers.
 *
 * The app draws edge to edge, so a page must leave the safe areas alone before it places anything:
 * `statusBarsPadding()` at the top, `navigationBarsPadding()` at the bottom, then these gaps on top
 * of that. Before this existed the connect page had no insets at all and its title sat on the status
 * bar, while the photo page used 4dp under the same bar.
 *
 * Values come from the spacing scale in `docs/design/design-language.md` (4, 8, 12, 16, 22, 32).
 */
val PageMargin = 16.dp

/** Gap between the safe area and the first thing on a page. Not measured from the screen edge. */
val PageTopGap = 16.dp

/** Gap between the last thing on a page and the gesture bar. */
val PageBottomGap = 16.dp

/**
 * Page titles are 28sp everywhere, including the connect pages.
 *
 * The design language used to give the connect pages a 34sp Large Title. That made the title jump
 * when moving between the connect page and the photo page, and next to a 13sp sub-line it read as
 * "bursting out". One size for every page title keeps the two screens feeling like one app.
 */
val PageTitleSize = 28.sp

/** Section and status titles: "找不到相机", "Wi-Fi 密码错误", dialog titles. */
val SectionTitleSize = 22.sp
