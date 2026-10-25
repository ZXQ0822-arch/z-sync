package app.zsync.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zsync.session.JourneyChrome
import app.zsync.session.JourneyPage
import app.zsync.session.JourneyState
import app.zsync.session.SEARCHING_LINE
import app.zsync.session.designFile
import app.zsync.log.QuickLog
import app.zsync.ui.theme.PageBottomGap
import app.zsync.ui.theme.PageMargin
import app.zsync.ui.theme.PageTopGap
import app.zsync.session.journeyChrome
import app.zsync.ui.components.CancelButton
import app.zsync.ui.components.ConfirmDialog
import app.zsync.ui.components.StoneButton
import app.zsync.ui.theme.Card
import app.zsync.ui.theme.CardSelected
import app.zsync.ui.theme.Ink
import app.zsync.ui.theme.Line
import app.zsync.ui.theme.Muted
import app.zsync.ui.theme.Phone
import app.zsync.ui.theme.Stone
import app.zsync.ui.theme.StoneInk

private val DialogScrim = Color(0x6B141416)
private val DialogCard = Color(0xFF48484C)

@Composable
fun JourneyScreen(
    state: JourneyState,
    version: String,
    onPassword: (String) -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onSaved: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chrome = journeyChrome(state)
    JourneyChromeScreen(
        chrome,
        state.typedPassword.orEmpty(),
        version,
        onPassword,
        onPrimary,
        onSecondary,
        onSaved,
        onConfirm,
        onDismiss,
    )
}

@Composable
private fun JourneyChromeScreen(
    chrome: JourneyChrome,
    password: String,
    version: String,
    onPassword: (String) -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onSaved: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Phone).testTag(designFile(chrome.page))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = PageMargin, end = PageMargin, top = PageTopGap, bottom = PageBottomGap),
        ) {
            chrome.eyebrow?.let {
                Text(it, color = Muted, fontSize = 11.sp, letterSpacing = 2.2.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
            if (chrome.spinner) {
                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.padding(bottom = 12.dp).size(22.dp))
            }
            Text(chrome.title, color = Ink, fontSize = chrome.titleSize.sp, fontWeight = FontWeight.Medium)
            if (chrome.page == JourneyPage.SecondAuto) {
                if (chrome.disconnected) {
                    Text(
                        "连接已断开，正在重新连接",
                        color = Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .background(Card, RoundedCornerShape(14.dp))
                            .border(1.dp, Line, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                    ) {
                        Text("注意", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "如果反复断开，可能是手机把没有网络的相机热点切走了。请到 WLAN 设置关闭「智能网络切换」。",
                            color = Muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    "已扫描 ${chrome.attempts.coerceAtLeast(1)} 次",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "通常几秒到 1 分钟，请稍等",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            chrome.lines.forEach { line ->
                Text(line, color = Muted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
            }
            chrome.callout?.let { Text(it, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp)) }
            chrome.lastCamera?.let { Text(it, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
            chrome.autoLine?.let { Text(it, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
            chrome.foundHeading?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp)) }
            chrome.rows.forEach { row ->
                val current = row.startsWith("正在连接")
                Text(
                    row,
                    color = Ink,
                    fontSize = 17.sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(if (current) CardSelected else Card, RoundedCornerShape(16.dp))
                        .then(if (current) Modifier.border(1.5.dp, Ink, RoundedCornerShape(16.dp)) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            if (chrome.page == JourneyPage.SecondReturn) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).background(Card, RoundedCornerShape(16.dp)),
                ) {
                    if (chrome.hotspots.isEmpty()) {
                        Text(SEARCHING_LINE, color = Ink, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp))
                    } else {
                        chrome.hotspots.forEachIndexed { index, name ->
                            if (index > 0) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Line))
                            }
                            Text(name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp))
                        }
                    }
                }
            }
            if (chrome.steps.isNotEmpty()) {
                if (chrome.stepsBoxed) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .background(Card, RoundedCornerShape(16.dp)),
                    ) {
                        chrome.steps.forEachIndexed { index, step ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .height(1.dp)
                                        .background(Line),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${index + 1}", color = Muted, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                                stepIcon(step)?.let { Icon(it, contentDescription = null, tint = Ink, modifier = Modifier.padding(end = 12.dp).size(18.dp)) }
                                Text(step, color = Ink, fontSize = 15.sp)
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        chrome.steps.forEachIndexed { index, step ->
                            if (index > 0) {
                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${index + 1}", color = Muted, fontSize = 15.sp, modifier = Modifier.width(28.dp))
                                stepIcon(step)?.let { Icon(it, contentDescription = null, tint = Ink, modifier = Modifier.padding(end = 12.dp).size(18.dp)) }
                                Text(step, color = Ink, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
            chrome.placeholder?.let { placeholder ->
                PasswordField(
                    value = password,
                    onValueChange = onPassword,
                    onDone = onPrimary,
                    modifier = Modifier.padding(top = 18.dp),
                    placeholder = placeholder,
                )
            }
            chrome.primary?.let { label ->
                StoneButton(label, enabled = chrome.primaryEnabled, arrow = true, onClick = onPrimary, modifier = Modifier.padding(top = 16.dp))
            }
            chrome.secondary?.let { label ->
                CancelButton(label, onClick = onSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            chrome.savedHeading?.let { heading ->
                Text(heading, color = Ink, fontSize = 16.sp, modifier = Modifier.padding(top = 22.dp))
            }
            chrome.savedName?.let { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Card, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .background(Stone, RoundedCornerShape(10.dp))
                            .clickable(enabled = chrome.savedActionEnabled, onClick = onSaved)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(chrome.savedAction ?: "连接", color = StoneInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = PageBottomGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(version, color = Muted, fontSize = 11.sp)
                // The only log route a tester has: the phone, no adb. Tapping hands the file to
                // whatever they want to send it with.
                Text(
                    "导出日志",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { QuickLog.share(context) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        chrome.dialog?.let { dialog ->
            ConfirmDialog(
                title = dialog.title,
                confirmLabel = dialog.right,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                dismissLabel = dialog.left,
            )
        }
    }
}

private fun stepIcon(step: String): ImageVector? = when (step) {
    "相机开机" -> Icons.Rounded.PhotoCamera
    "按 MENU" -> Icons.Rounded.Settings
    "点击类似扳手的图标" -> Icons.Rounded.Build
    "连接至智能设备" -> Icons.Rounded.Smartphone
    "Wi-Fi连接" -> Icons.Rounded.Wifi
    "建立Wi-Fi连接" -> Icons.Rounded.Link
    else -> null
}
