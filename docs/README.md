# 文档

给人看的说明都在这里。给 AI 的入口是仓库根上的 [AGENTS.md](../AGENTS.md)。

**当前版本：0.1.38（38）。** 源码和手机一致。小米 15 上跑通了主干：连接、看照片、同步到相册、两处删除、后台传图。

## 要查什么，看哪份

| 你要查什么 | 文件 |
| --- | --- |
| 这应用是干什么的、做到哪了、还差什么 | [需求](requirements.md) |
| 每条任务完成没有 | [任务清单](backlog/task-tracker.md) |
| 已知缺陷和修没修 | [缺陷](bugs/bugs.md)、[2026-10-25 这一轮](bugs/2026-10-25.md) |
| 已经写进版本的功能 | [功能](features/features.md) |
| 数据和状态怎么流转 | [技术架构](architecture/architecture.md)、[连接可靠性](architecture/connection-reliability.md) |
| 人怎么走完一次传图 | [使用路径](architecture/user-journey.md) |
| 别人第一次怎么设置手机和相机 | [使用手册](guides/setup.html)（网页版，带目录，以此为准）；纯文本摘要 [setup.md](guides/setup.md) |
| 界面长什么样、颜色字号间距 | [设计语言](design/design-language.md)、[界面稿](design/index.html) |
| 某个难题是怎么想出来的 | [方案](#方案) |
| 某一版在手机上到底出了什么 | [调试记录](#调试记录) |
| 怎么改代码、怎么发版 | [改代码前对照](process/before-code.md)、[分支](process/branch-strategy.md)、[手机调试](process/dev-setup.md)、[AI 调试循环](process/dev-loop.md) |

## 方案

难题的推演过程，留着是为了不用重新想一遍。

- [后台断流方案](solution/coloros-background-drop.md)：ColorOS 冻结后台应用并销毁 socket 的完整时间线、试过的三种手段为什么都无效，以及小米上为什么「省电策略无限制」就够了。
- [加载进度](solution/loading-progress.md)：为什么现在报不出分母，怎么把枚举和读取拆开，照片页和通知各自显示什么。**还没实现。**
- 早期记录：[Z30 Keep-Alive](solution/Nikon%20Z30%20Keep-Alive.md)、[SeamlessDeletionProtocol](solution/SeamlessDeletionProtocol.md)、[Infinite Scroll & Lazy Loading](solution/Infinite%20Scroll%20%26%20Lazy%20Loading%20Solution.md)。

## 发布

对外发的版本，说明文字在这里留一份，方便复制到 GitHub Release。

- [v0.1.40](release/v0.1.40.md)：第一个对外发布的版本。

发布做的事：升 `versionCode` / `versionName`、`tools/package-release.sh` 打包、核对 SHA-256、`git tag -a v<版本>`、在 GitHub 建 Release 上传 apk 和 `.apk.sha256` 两个附件。`dist/` 不进 git，所以 apk 只能走 Release 附件。

## 调试记录

按版本一个目录，放在 [debug/](debug/)。每一版有 `conversation.md`（这一轮发生了什么、结论是什么）和当时的原始 `logcat`。规则见 [debug/rules.md](debug/rules.md)。

应用自己也记日志：连接页底部「导出日志」可以把当前日志分享出来，超过 6 小时自动清理。看不到手机文件系统时用这个。

## 界面稿

- 图标母版在 [assets/icon/](../assets/icon/README.md)：`icon.svg` 是母版，安卓的三层自适应资源在 `androidApp/src/main/res/`。
- [design/index.html](design/index.html) 是入口，`pages/` 下是当前这一套页面稿（含两份提案：[删除界面](design/pages/delete-ui-proposal.html)、[页面框架与标题字号](design/pages/frame-spacing-proposal.html)）。
- `01a/` 和 `02a/` 是早期的走查稿，留着看演变，**不是现在的实现**。现在的实现以 `pages/` 和代码为准。

## 约定

- 同一件事只在一种文档里写正文，其它地方给链接。功能写在 `features/`，缺陷写在 `bugs/`，任务状态只写状态和链接。
- 已经做完的写清进了哪个版本；没在手机上逐条复核的，明确写「代码已合入，未逐条复核」，不要写成已验证。
- 不要在文档里写具体日期以外的推测当结论。
