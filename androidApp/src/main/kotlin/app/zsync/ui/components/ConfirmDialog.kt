package app.zsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zsync.ui.theme.Ink

/**
 * The one confirmation dialog used by every screen: a full-screen scrim, a centred Card and two
 * buttons. Tapping the scrim, pressing back or the left button only dismisses; the right one
 * performs the action, so a confirm can never fire by accident.
 *
 * [destructive] paints the right button with the Danger outline (delete), otherwise it is the Stone
 * primary (confirm). The dialog owns no state: it reports through [onDismiss] and [onConfirm] only,
 * so it cannot change the page on its own.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DialogScrimColor)
                .clickable(onClick = onDismiss)
                .testTag("confirm-scrim"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .background(DialogCardColor, DialogCardShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(20.dp)
                    .testTag("confirm-dialog"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CancelButton(
                        dismissLabel,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).testTag("confirm-left"),
                    )
                    if (destructive) {
                        DangerButton(
                            confirmLabel,
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f).testTag("confirm-right"),
                        )
                    } else {
                        StoneButton(
                            confirmLabel,
                            onClick = onConfirm,
                            pill = false,
                            modifier = Modifier.weight(1f).testTag("confirm-right"),
                        )
                    }
                }
            }
        }
    }
}
