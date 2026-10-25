package app.zsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zsync.ui.theme.Card
import app.zsync.ui.theme.Ink
import app.zsync.ui.theme.Muted
import app.zsync.ui.theme.Stone
import app.zsync.ui.theme.StoneInk

/**
 * Offers to put the app on the system's battery allowlist.
 *
 * ColorOS froze the connected app in the background and closed its sockets at the same moment, so
 * the camera saw the session end and switched its own Wi-Fi off. A foreground service does not stop
 * that; only the allowlist does. Copy stays plain: say what goes wrong, say what the button buys.
 */
@Composable
fun BatteryHintCard(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text("切到别的应用时连接会断开", color = Ink, fontSize = 15.sp)
        Text(
            "请在系统设置里允许 Z-Sync 后台运行，传图时切出去才不会断。",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("以后再说", color = Muted) }
            Text(
                "去设置",
                color = StoneInk,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Stone, RoundedCornerShape(14.dp))
                    .clickable(onClick = onAllow)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
