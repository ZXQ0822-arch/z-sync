package app.zsync.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zsync.BuildConfig
import app.zsync.session.LinkFailure
import app.zsync.session.LinkPhase
import app.zsync.ui.theme.PageBottomGap
import app.zsync.ui.theme.PageMargin
import app.zsync.ui.theme.PageTitleSize
import app.zsync.ui.theme.PageTopGap
import app.zsync.session.homeActionLabel
import app.zsync.session.releasesConnectCooldown
import app.zsync.session.SCAN_HINT
import app.zsync.session.scanCountLabel
import app.zsync.session.showsWaitSpinner
import app.zsync.ui.components.StoneButton
import app.zsync.ui.theme.Card
import app.zsync.ui.theme.Ink
import app.zsync.ui.theme.Line
import app.zsync.ui.theme.Muted
import app.zsync.ui.theme.Phone
import app.zsync.ui.theme.Stone

@Composable
fun ConnectScreen(
    phase: LinkPhase,
    passwordSaved: Boolean,
    lastCamera: String?,
    onSubmitPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var previous by remember { mutableStateOf<LinkPhase?>(null) }
    LaunchedEffect(phase) {
        if (previous == LinkPhase.Failed(LinkFailure.BadPassword) && phase is LinkPhase.NeedPassword) password = ""
        previous = phase
    }
    val wrongPassword = phase is LinkPhase.Failed && phase.failure == LinkFailure.BadPassword
    val home = phase is LinkPhase.Ready || phase is LinkPhase.NeedPassword
    val askPassword = wrongPassword || home
    val reconnect = passwordSaved && home && password.isBlank() && !wrongPassword
    val copy = copyFor(phase, passwordSaved)
    var cooling by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (releasesConnectCooldown(phase)) cooling = false
    }
    val requestOpen = phase is LinkPhase.Connecting || phase is LinkPhase.AwaitingSystemAllow
    LaunchedEffect(cooling) {
        if (!cooling) return@LaunchedEffect
        delay(1_800)
        cooling = false
    }
    val connectEnabled = !cooling && !requestOpen && (reconnect || !askPassword || password.isNotBlank())
    val submit = {
        if (connectEnabled) {
            cooling = true
            onSubmitPassword(password)
        }
    }
    val connect = {
        if (connectEnabled) {
            cooling = true
            onConnect()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Phone)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = PageMargin, end = PageMargin, top = PageTopGap, bottom = PageBottomGap),
    ) {
        if (passwordSaved && !home) {
            LastCameraBanner(
                name = cameraLabel(lastCamera),
                hint = when (phase) {
                    is LinkPhase.Searching -> "正在寻找"
                    LinkPhase.AwaitingSystemAllow -> "正在等待"
                    is LinkPhase.Connected -> "已连接"
                    else -> null
                },
            )
        }
        Text(
            text = copy.eyebrow,
            color = Muted,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
        )
        Text(
            text = copy.title,
            color = Ink,
            fontSize = PageTitleSize,
            fontWeight = FontWeight.Medium,
            lineHeight = 40.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = copy.body,
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
        )
        (phase as? LinkPhase.Failed)?.detail?.let { detail ->
            Text(
                text = detail,
                color = Ink,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 22.dp),
            )
        }
        if (phase is LinkPhase.Ready || phase is LinkPhase.NeedPassword || phase is LinkPhase.WaitingForHotspot) {
            Step(number = "1", label = "打开与智能设备的 Wi-Fi")
            Step(number = "2", label = "看相机屏幕上的密码")
        }
        if (askPassword) {
            PasswordField(
                value = password,
                onValueChange = { password = it },
                onDone = { submit() },
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (passwordSaved && home) {
            Text(
                "上次连接 ${cameraLabel(lastCamera)}",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        when {
            wrongPassword -> StoneButton("重试", enabled = connectEnabled, onClick = submit)
            reconnect -> StoneButton(homeActionLabel(passwordSaved, password), enabled = connectEnabled, onClick = connect)
            askPassword -> StoneButton(homeActionLabel(passwordSaved, password), enabled = connectEnabled, onClick = submit)
            phase is LinkPhase.Ready -> StoneButton(homeActionLabel(passwordSaved, password), enabled = connectEnabled, onClick = connect)
            phase is LinkPhase.WaitingForHotspot ||
                (phase is LinkPhase.Failed && phase.failure == LinkFailure.HotspotOff) ->
                StoneButton("我已打开", enabled = connectEnabled, onClick = connect)
            phase is LinkPhase.Failed -> StoneButton("重试", enabled = connectEnabled, onClick = connect)
            showsWaitSpinner(phase) -> {
                CircularProgressIndicator(
                    color = Stone,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(36.dp),
                )
                TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("返回", color = Ink, fontSize = 15.sp)
                }
            }
            phase is LinkPhase.Searching -> {
                TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("返回", color = Ink, fontSize = 15.sp)
                }
                StoneButton("连接", enabled = connectEnabled, onClick = connect)
            }
            phase is LinkPhase.Connected -> {
                TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("断开", color = Ink, fontSize = 15.sp)
                }
            }
        }
        Text(
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

private fun cameraLabel(ssid: String?): String {
    val name = ssid?.trim()?.removeSurrounding("\"")
    return if (name.isNullOrEmpty()) "尼康 Z30" else name
}

@Composable
private fun LastCameraBanner(name: String, hint: String?) {
    Row(
        modifier = Modifier
            .padding(bottom = 14.dp)
            .background(Card, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("上次连接：$name", color = Ink, fontSize = 12.sp)
        if (hint != null) {
            Text("· $hint", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

private data class Copy(val eyebrow: String, val title: String, val body: String)

private fun copyFor(phase: LinkPhase, passwordSaved: Boolean): Copy {
    return when (phase) {
        is LinkPhase.Searching -> Copy(
            eyebrow = "Z 30",
            title = scanCountLabel(phase.scans),
            body = SCAN_HINT,
        )
        LinkPhase.WaitingForHotspot -> hotspotCopy()
        LinkPhase.AwaitingSystemAllow -> Copy(
            eyebrow = "仍在 Z-Sync",
            title = "等系统确认",
            body = "正在等系统把手机加进相机热点。弹出系统窗口时点这一台相机即可，密码不用再填。",
        )
        LinkPhase.Connecting -> Copy(
            eyebrow = "Z 30",
            title = "正在连接",
            body = "热点已经开着的话，通常只要一两秒。",
        )
        is LinkPhase.Connected -> Copy(
            eyebrow = "Z 30",
            title = "已连上相机",
            body = if (phase.internetAvailable) "流量正常。" else "手机外网中断。",
        )
        is LinkPhase.Failed -> when (phase.failure) {
            LinkFailure.HotspotOff -> hotspotCopy()
            LinkFailure.BadPassword -> Copy(
                eyebrow = "Z 30",
                title = "密码错了",
                body = "再看一次相机屏幕上的密码。",
            )
            LinkFailure.OffCamera -> Copy(
                eyebrow = "Z 30",
                title = "不在相机热点",
                body = "手机现在不在相机的 Wi-Fi 上。打开相机热点后会再连，已保存的密码还在。",
            )
            LinkFailure.Dropped -> Copy(
                eyebrow = "Z 30",
                title = "相机断开了",
                body = "热点关了，或者手机离相机太远。点重试会用保存的密码再连。",
            )
            LinkFailure.ClientBusy -> Copy(
                eyebrow = "Z 30",
                title = "相机正连着别的应用",
                body = "先退出 SnapBridge，再回来。",
            )
            LinkFailure.Unknown -> Copy(
                eyebrow = "Z 30",
                title = "没有连上",
                body = "看一下热点和密码，然后再试一次。",
            )
        }
        LinkPhase.Ready, LinkPhase.NeedPassword -> Copy(
            eyebrow = "Z 30",
            title = "连接这台相机",
            body = "只做一次。之后打开就是照片，流量保持可用。",
        )
    }
}

private fun hotspotCopy() = Copy(
    eyebrow = "Z 30",
    title = "热点没开",
    body = "在相机上建立 Wi-Fi 连接，然后回来。密码不用再填。",
)

@Composable
private fun Step(number: String, label: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(number, color = Muted, fontSize = 15.sp, modifier = Modifier.width(28.dp))
            Text(label, color = Ink, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "相机上的密码",
) {
    var visible by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = Ink, fontSize = 15.sp),
        cursorBrush = SolidColor(Stone),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().height(52.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, if (value.isEmpty()) Line else Ink.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                Box(
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 15.sp)
                    inner()
                }
                Icon(
                    if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                    tint = Muted,
                    modifier = Modifier.size(20.dp).clickable { visible = !visible },
                )
            }
        },
    )
}
