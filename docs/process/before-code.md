# 改代码前对照

改连接、照片、恢复、删除或同步的行为、页面或文案之前，先对照，再改代码。

## 以哪份为准

页面上写什么、有哪些按钮、弹窗里是哪一句，以使用路径点名的 HTML 设计稿为准。使用路径只说明这些稿怎么串起来：何时搜索、何时加入、何时释放网络。设计语言管颜色、按钮样式和弹窗左右。

设计稿和使用路径或缺陷记录不一致时，改代码跟设计稿。先把使用路径改成和稿相同的句子，再改代码。不要把缺陷里的旧文案写进页面。

缺陷记录和使用路径里标着「当时记录」的段落只说明当时看到什么。它们不是页面文案，现在长什么样以设计稿和代码为准。

## 当前要打开的稿

第一次连接：

- `docs/design/01a/01a-home.html`
- `docs/design/01a/01b-connecting-back.html`
- `docs/design/01a/01c-cant-find-camera.html`
- `docs/design/01a/01d-password-error.html`

第二次连接：

- `docs/design/02a/02a-home.html`
- `docs/design/02a/02a-home-return.html`
- `docs/design/02a/02a-connecting.html`
- `docs/design/02a/02c-cant-find-camera.html`
- `docs/design/02a/02d-password-error.html`

连接成功之后：

- `docs/design/pages/06-photos.html`
- `docs/design/pages/07-download-progress.html`

`docs/design/pages/` 里没被使用路径点名的页，包括等系统确认、2B、旧的找不到相机和热点没开，不是当前目标流程。

## 断流恢复

使用路径、设计语言和 `docs/design/pages/06-photos.html` 都写「正在恢复相机连接」。照片页稿里的状态条默认隐藏，需要重建会话时再出现。

恢复时的行为按使用路径执行：留在照片页，保留已显示的照片，不因 PTP 失败释放 Wi-Fi，不跳回连接页。只有 Android `onLost` 才重获热点。只有人确认「取消连接」才释放网络并回到对应连接首页。按钮按设计语言：左「取消连接」，右「继续连接」。
