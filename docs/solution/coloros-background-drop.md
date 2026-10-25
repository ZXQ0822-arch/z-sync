# 后台断流：根因与处理结果

> **结果先写在这里。**
>
> **小米 / HyperOS 已解决**：省电策略设「无限制」+ 允许通知，实测切后台十几分钟不断、热点不关。要求已写进 [上手说明](../guides/setup.md)。
>
> **一加 / ColorOS 结案，不再处理**：前台服务、Doze 白名单、厂商「完全允许后台行为」三种手段全部验证无效，系统仍会冻结后台应用并销毁它的 socket。根因在下面，留作记录。

材料：`docs/debug/0.1.29/`、`0.1.30/`、`0.1.31/`、`0.1.32/` 四份抓取（`phone-logs.txt`、`process-state.txt`）。

## 一句话结论

切到别的应用后，ColorOS 的 `OplusHansManager` 进入 `StrictMode-1`，把 Z-Sync 进程冻住（cgroup 冻结），**同一毫秒 `netd` 把它仅有的 2 个 PTP socket 直接销毁**。相机收到 RST/FIN，判定会话结束，关掉了自己的 Wi-Fi。应用被冻着无法反应，等用户回来解冻后才慢半拍跳回连接页。

不是网络被切走，不是进程被杀，也不是心跳间隔太长。

## 完整因果链（带日志证据）

| # | 时间 | 日志 | 含义 |
|---|---|---|---|
| 1 | 11:49:49.512 | `ptp session ready, media page allowed` | 连上，照片页开始刷缩略图 |
| 2 | 11:49:49.667 | `Background started FGS: Allowed ... cmp=.../CameraSessionService` | 前台服务已经起来了 |
| 3 | 11:49:58.266 | `OplusHansManager: uid=10027 app.zsync M enter()` | 用户切出应用 |
| 4 | 11:50:02.377 | `operation=GetObjectInfo transaction=230` | 冻结前最后一个 PTP 请求，仍在等回包 |
| 5 | 11:50:02.396 | `OplusCpuHighLoad: loadLevel = 2`，`cpuLoad=85` | 缩略图狂刷把 CPU 顶到 85% |
| 6 | 11:50:02.397 | `OplusHansManager: enter StrictMode-1`，`freezeAllApps uid=10027` | 系统进入严格模式，一次冻 6 个 uid |
| 7 | 11:50:02.400 | `freeze uid: 10027 app.zsync pids: [14684] scene: \|StrictMode-1\|` | **冻结** |
| 8 | 11:50:02.401 | `netd: destroyAllSocketForUid:10027` | **销毁 socket** |
| 9 | 11:50:02.405 | `DestroyAllSocket 2 sockets for uids{ 10027 } skip={0}` | 2 个 = PTP command + event |
| 10 | 11:50:02.406 | `OAppNetControlService: Close socket:[10027] cause:App bg(IMMEDIATELY)` + 拉进网络黑名单 | 连网络权限一起掐 |
| 11 | 11:50:07.690 | `wpa_supplicant: disconnect reason 14, missing the beacons continuously` | 手机 +5.3s 才判定 AP 没了 |
| 12 | 11:50:07.691 | `OplusDisconnectOptimize: delay disconnect after 4000ms` | OPPO 再拖 4 秒 |
| 13 | 11:50:11.807 | `CONNECTED → DISCONNECTED` | 距冻结 9.4 秒，这段时间手机一直显示「已连接」 |
| 14 | 11:50:30.523 | `unfreeze uid: 10027` | 用户回来 |
| 15 | 11:50:30.538 | `ptp keepalive skipped SocketException` | 心跳失败，被应用吞了 |
| 16 | 11:50:30.540 | `camera network lost network=306` | `onLost` 这时才被派发 |
| 17 | 11:50:30.555 | `media session lost; going back to the connect screen` | 静默跳回连接页 |
| 18 | 11:50:33.411 | `scan cache=19 camera=false` | 19 个 AP 里没有相机，证明相机真关了 Wi-Fi |
| 19 | 11:50:34.629 | `Killing 14684 ... remove task` | 是用户划掉，不是崩溃 |

三个关键结论：

1. **socket 是被系统杀死的**。`destroyAllSocketForUid:10027`，`skip={0}` 没有任何豁免，`2 sockets` 正好等于 `NikonWire` 的 `command` + `events`。
2. **前台服务挡不住**。FGS 在 11:49:49.667 启动成功、之后无 stop，进程状态也从 TOP 走到 FOREGROUND_SERVICE，仍然在 11:50:02.400 被冻。
3. **那 9.4 秒就是「手机还显示尼康连着」**。beacon 判定 5.3s + OPPO 4s 延迟，是系统链路状态滞后，不是应用不刷新。

## 为什么常见招数都不行（否定清单）

| 招数 | 为什么没用 |
|---|---|
| 把心跳从 30s 缩到 5–10s | socket 已经被 `netd` 销毁，不是「静默」。0.1.27-1 日志证明相机能容忍 30s 静默，所以缩短心跳完全无效 |
| `PARTIAL_WAKE_LOCK` | 屏幕亮着（`LcdOn`），CPU 没睡；wakelock 不防 vendor 冻结 |
| `WifiLock WIFI_MODE_FULL_HIGH_PERF` | API 29 起废弃，对 `WifiNetworkSpecifier` 网络也不生效 |
| 把 `requestNetwork` 搬进 Service | 同一进程，一起被冻，socket 一样被销毁 |
| 解冻后自动重连 | 相机 Wi-Fi 已经关了（第 18 行 `camera=false`），无 AP 可连 |
| 只靠前台服务 | 已证伪（第 2、7 行） |

结论：**能真正阻止这次冻结的只有一件事——让 Z-Sync 进系统的「白名单」**（电池优化白名单 / ColorOS 的「允许后台运行」）。其余都是「发生了怎么体面收场」和「怎么降低触发概率」。

## 修复方案（按优先级）

### P0 — 拿系统白名单（唯一能阻止冻结的手段）

**做什么**：引导用户把 Z-Sync 加入电池优化白名单 + ColorOS 的「允许后台运行」。

**为什么**：ColorOS 的冻结器在冻结前会查白名单；`destroyAllSocketForUid` 里的 `skip={0}` 说明存在跳过机制，白名单应用通常会被跳过。FGS 已被证明不在这个名单里。

**怎么实现**：
1. `AndroidManifest.xml` 加 `<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>`。
2. 在连接页（`ConnectRoute`）加一个状态检查：`PowerManager.isIgnoringBatteryOptimizations(packageName)` 为 false 时，显示一张卡片：「为了让切后台传图不中断，请允许 Z-Sync 后台运行」+ 按钮。
3. 按钮触发：
   ```kotlin
   startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
       .setData(Uri.parse("package:$packageName")))
   ```
   系统弹「允许 Z-Sync 始终在后台运行？」→ 用户点允许。
4. 第二步（ColorOS 特有，无法自动）引导到系统设置里的「应用 → Z-Sync → 允许后台运行 / 自启动」，给一行文字 + 跳 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`。

**风险 / 注意**：
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 在 Google Play 有政策限制，本项目是侧载分发，无影响，但要记录。
- 电池优化白名单是否等于 ColorOS 冻结白名单，**必须真机验证**（见测试矩阵）。若不够，以 ColorOS「允许后台运行」为准。

**验证**：白名单开启后重跑抓取，看 `freezeAllApps uid=10027` 是否还出现、`destroyAllSocketForUid:10027` 是否消失。

### P1 — 冻结发生时诚实报断（不吞错误）

**做什么**：心跳失败不再吞掉，判定会话丢失后提示「相机已断开」并回连接页。

**为什么**：这次日志里，解冻后 `keepalive skipped`、然后静默跳回连接页，用户不知道发生了什么。这条与机型无关，是独立必修的。

**怎么实现**：
1. `CameraRoll.keepaliveIfIdle()` 不再 `catch 后 return false`，改成返回一个结果（成功 / 超时 / 硬错误），或直接上抛。硬错误（`SocketException` / `ECONNABORTED`，即 socket 被销毁）一次即判丢失；超时需连续 2 次。
2. `PhotoViewModel` 的 keepalive 循环里，收到「丢失」就调 `onSessionLost()`（已有回调，接到 `connect.link.sessionLost()`）。
3. UI 上给一句「相机已断开，请重新打开相机的 Wi-Fi」，而不是无声跳转。复用现有 recovery UI 或加一行。

**为什么 `ECONNABORTED` 是关键信号**：`Software caused connection abort` 就是 socket 被外部销毁的特征，和相机主动关 Wi-Fi、网络超时都能区分。

### P2 — 降低触发概率：缩略图限速

**做什么**：缩略图预取限速；应用不可见时暂停加载。

**为什么**：日志里 `cpuLoad=85` 触发了 `OplusCpuHighLoad → StrictMode-1`。这 85% 很大程度是刷缩略图刷出来的（每秒 ~20 个 PTP 操作 + JPEG 解码）。降下来就不容易招来严格模式。这是「降低概率」，不是根治。

**怎么实现**：
1. `PhotoViewModel.loadListedRoll()` / `append()` 的加载循环里加 `delay`（如每批之间 20–30ms）或并发上限。
2. 用生命周期观察者在 `onStop` 时暂停加载，`onStart` 恢复。
3. 可选：解码放到更节流的调度上。

### P3 — 体验细节

- 前台服务通知加逐张进度（「正在保存 3/20」），现在是只有批次开始/结束。
- 事件通道 `soTimeout=30_000` 是真 bug（`NikonWire.open()` 对 event socket 也设了 30s 读超时，30s 后事件读线程死掉，见 0.1.27-1 日志）。这次是被 socket 销毁抢先了（`SocketException` 不是 `SocketTimeoutException`），但前台长时间会话会踩到，顺手修：event socket 不设读超时，或超时后继续循环。

## 测试矩阵（真机验证顺序）

| 步骤 | 条件 | 期望 | 判定 |
|---|---|---|---|
| 基线 | 当前 0.1.29（有 FGS、无白名单） | 已复现冻结 | 已知 |
| T1 | 加白名单（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 后允许） | `freezeAllApps uid=10027` 不再出现，socket 不被销毁 | 通过则 P0 生效 |
| T2 | T1 不通过 → ColorOS「允许后台运行」手动打开 | 同上 | 通过则 P0 生效（需引导页） |
| T3 | P1 合入后，不设白名单直接切后台 | 切回来立刻看到「相机已断开」，不是静默跳转 | P1 生效 |

每个 T 都用 `tools/capture-background-drop.sh 120`，对照三处关键行：`freeze uid: 10027`、`destroyAllSocketForUid:10027`、`ptp keepalive skipped`。

## 环境注意事项

- 测试机是 **一加 / ColorOS**（日志里的 `OplusHansManager`、`OplusDisconnectOptimize`、`OAppNetControlService`）。
- 抓取时手机上还开着代理类应用（也在系统的冻结名单里）。代理或 VPN 可能影响 ColorOS 的「无网判定」，复测时建议先关掉，排除干扰。
- 版本对齐：抓取目录按源码版本号命名，抓之前确认手机装的 versionCode 和源码一致（这次中间有一段是 0.1.29 源码、0.1.28 装机）。

## 待定（这次不做）

- **小米 / HyperOS**：冻结机制不同（通常只 SIGSTOP，不一定销毁 socket）。要在小米 15 上同样复现一次，看有没有 `destroyAllSocketForUid`：没有的话，FGS 可能就够；有的话走白名单。等 ColorOS 方案落地后再说。
- **相机侧**：Z30 在会话结束后会关 Wi-Fi。如果相机菜单里有「保持 Wi-Fi 开启」，解冻后自动重连才可能，否则只能让用户重开相机 Wi-Fi。查一下 Z30 菜单再决定。

## 更新 2026-10-25 晚：小米已解决，ColorOS 放弃

### 小米 / HyperOS 有效

小米 15 上把省电策略设成 **「无限制」（不采取任何限制措施）** 之后，实测：

- 连上相机后切到微博等应用，用移动数据上网，**11 分钟以上仍保持连接**，相机热点没关。
- 通知栏一直显示 Z-Sync 的前台服务，回来后照片页直接可用，不用重连。
- 也就是说，小米这边「前台服务 + 电池省电策略无限制」这一套是**有效的**，问题解决。

所以在 MIUI/HyperOS 上，结论和 ColorOS 相反：厂商白名单确实被尊重。0.1.30–0.1.32 那段把卡指向「去设置」，对小米是对的路径。

### ColorOS 结案：不再处理

一加（Android 14 ColorOS）上三种手段全部无效（前台服务、Doze 白名单、厂商「完全允许后台行为」），已由 chuchu issue #100 独立佐证：`OplusHansManager` 在应用离开前台约 5 秒后冻结 uid 并让 `netd` 销毁其全部 socket，前台服务类型不在豁免之列。这条不再投入，记录下来备查。

### 已记录但暂不做的方向：重连 + 续传

社区（`AchooLuv/NikonLink`、`aero-shutter-mobile`，都是尼康 PTP/IP 客户端）的主流做法是**不防断、断了自己恢复**：

1. **重连**：掉线后指数退避重连（1 → 30 秒），拿到网络就重开 PTP 会话。
2. **续传**：大文件分块写盘 + `.part` 断点文件，中断后从已写入字节继续。

我们的代码里已经有 `pausedSync` / `resumeSaveIfPaused` 和 `RecoveryStep.WaitingToReopen` 这套半成品。**暂不实现**，作为标记留在这里：如果以后还要在 ColorOS 上做后台传图，或者想在小米上把「切太久被系统断掉」也接住，就按这两步补齐。注意相机在会话断开后会自己关 Wi-Fi（Z30 无「保持 Wi-Fi 开启」设置），所以续传前需要用户重新打开相机热点。
