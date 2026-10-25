package app.zsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zsync.ui.theme.Card
import app.zsync.ui.theme.Ink
import app.zsync.ui.theme.Line

/**
 * One transient message style for the whole app. It reuses the same Ink, medium-weight treatment the
 * connect pages use for "请确认相机 Wi-Fi 已打开", so a prompt never looks like a different app.
 *
 * Callers pass the text and, when the toast may be dismissed, the dismiss callback; the toast itself
 * owns no state and never navigates, so it cannot move the person off the page.
 */
@Composable
fun ZSyncToast(
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Text(
        text,
        color = Ink,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Card, RoundedCornerShape(14.dp))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}
