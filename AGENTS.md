# Z-Sync

给在这个仓库里做事的 AI。人看的长文在 `docs/`。这里只写现在是什么样、改的时候守什么、哪份文档说了算。

## 做事时

下面这些从一开始做事就生效。写代码、改文档、提交、监控，都算。不只是修缺陷。

- 只要在做事，1 分钟进度监控就必须在跑。这是硬性要求，不是建议。从开始做事起一直报到用户说停，或全部事情做完。监控没在跑，先把它开起来，再做别的。每次用一句话说明：正在做哪一条、代码有没有在改、还没提交的提交落下来没有。
- 一件事几分钟没有进展，就算卡住。立刻停掉这个卡住的任务或代理，换一个新的重做同一件事。提交、修缺陷、监控都算。不要空等。
- 子代理在做事时出错、走错、或卡住，立刻停掉它。先留下它已经做到哪、上下文是什么，再开一个新的从那里接着做。
- 有一段时间没有在改东西，就立刻做下一条还没做的事，不要空等。
- 修缺陷之前，先把当前的 `docs/bugs/bugs.md` 和 `docs/requirements.md` 单独提交一次。只包含这两份文档。
- 功能从当前 `master` 拉 `feature/<短英文名>`，缺陷拉 `bug/<短英文名>`。一条分支只做一件事。测过再合回 `master`。细节见 `docs/process/branch-strategy.md`。
- 只在本地提交，不推远程。提交说明用英文。一次提交只做一件事。每个缺陷单独一次小提交。
- 全部缺陷都改完并测过之后，才升版本号。版本号仍然单独一次提交。
- 每加完一个修复，先把已经通过的测试再跑一遍，通过了再做下一条。
- 测试覆盖重要分支和场景。不为凑测试而写。

## 这是什么

Android 应用，对外名字 **Z-Sync**，包名 `app.zsync`。手机加入尼康 Z30 的热点，用 PTP/IP（TCP 15740）看照片、同步到系统相册、从相机或从本次下载里删除。安装名和界面不写相机品牌。

日常手机是一加 9RT。相机热点名类似 `NIKON_Z_30_12345`。日志标签 `Z-Sync`。密码只记长度，不记内容。

## 现在的版本

- 手机上装的是 **0.1.38**。连接页底部应是 `v0.1.38 (38)`。
- 源码也是 **0.1.38**。
- 小米 15 上验过主干：连接、看照片、同步到相册、两处删除、后台传图。
- 没有明确要求时，不要安装。手机上还有哪条没逐条复核，见 `docs/bugs/bugs.md` 开头的说明。

## 改代码时

- 改连接、照片、恢复、删除或同步之前，先按 `docs/process/before-code.md` 打开设计稿。页面文案以设计稿为准。
- 改了 `shared/`，提交前跑 `./gradlew :shared:test`。
- 要装到手机才把 `androidApp/build.gradle.kts` 的 `versionCode` 加 1，并改 `versionName`。装之前和装之后用下面的 `adb` 对版本和 SHA-256。对不上不要让人重测。
- 手机调试读 `docs/process/dev-setup.md`。`adb` 不进仓库：自己下 platform-tools 放到仓库根 `tools/adb/`，用 `ADB=tools/adb/adb`。小米无线调试在手机加入相机热点后会断，用 USB 网络共享保住；一加用数据线。安装前对版本和源码时间，旧了就重编，然后 `"$ADB" install -r`，再对版本和 SHA-256。步骤以那份文档为准。
- 手机上只动 `app.zsync`：安装、启动、它自己的日志和截图。不改系统设置，不碰别的应用。
- 后台断流不要再去"修"：小米靠用户在系统里把省电策略设成无限制（已解决），一加 / ColorOS 是系统冻结应用并销毁 socket，应用层无解（已结案）。原因在 `docs/solution/coloros-background-drop.md`，别再试着缩心跳、加 wakelock 或搬 `requestNetwork`。
- Gradle 在沙箱里会失败，需要在沙箱外跑。

## 代码在哪

```text
shared/          连接状态机、PTP 封包、排序。不引用 Android。
androidApp/      Compose 界面、Wi-Fi、相机会话、系统相册。
```

连接状态在 `shared/.../session/LinkMachine.kt`。扫描和加入热点在 `androidApp/.../link/`。照片和本次下载在 `androidApp/.../ui/photos/`。相册在 `PhoneGallery.kt`，目录是 `Pictures/Z-Sync`。

现在的行为和还没做的功能，以 `docs/requirements.md` 的「当前范围」和「还要做」为准。数据怎么走，以 `docs/architecture/architecture.md` 为准。那里标成「尚未落地」的，不要当成已经写进应用。

## 文档

总目录：`docs/README.md`。

| 要查什么 | 文件 |
| --- | --- |
| 背景、当前范围、还要做的功能 | `docs/requirements.md` |
| 技术架构和数据怎么走 | `docs/architecture/architecture.md` |
| 人怎么走 | `docs/architecture/user-journey.md` |
| 故事和早期 Spike | `docs/backlog/backlog.md` |
| 每一条做到没有 | `docs/backlog/task-tracker.md` |
| 缺陷和勾选 | `docs/bugs/bugs.md` |
| 已写进版本的功能 | `docs/features/features.md` |
| 照片和视频目录怎么组织 | `docs/features/media-catalog.md` |
| 多相机延期参考 | `docs/features/multi-camera.md` |
| 拍照风格的 11 套数字 | `docs/features/picture-styles.md` |
| 某个难题怎么想出来的 | `docs/solution/coloros-background-drop.md`、`docs/solution/loading-progress.md` |
| 某一版在手机上出了什么 | `docs/debug/<版本>/conversation.md` |
| 别人第一次怎么设置手机和相机 | `docs/guides/setup.html`（以此为准） |
| 手机怎么连上、adb 在哪 | `docs/process/dev-setup.md` |
| 界面稿 | `docs/design/index.html` |

连接可靠性 QR 系列、媒体目录 MC 系列、以及 0.1.21 之前那批 BUG 都已经合入 0.1.21 及之后的版本，状态在任务清单里。**现在真正剩下的只有三件**：

1. 默认只加载最近 100 张，以及按时间或数量选加载范围（分批加载）。
2. 加载进度显示成 `231/1000`（照片页状态行和通知各一处）。方案在 `docs/solution/loading-progress.md`，它是第 1 条的前置。
3. 若干页面细节优化，具体项待列。

帮助（QL-22）和反馈（QL-23）不做。多相机（QL-06）与用户可配置多品牌前缀（QL-07）已延期，不算当前路线。两处写了同一件事时，改功能文档里的那一份，任务清单只保留状态和链接。
