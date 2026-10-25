# Z30 用户媒体目录

**状态：** 已定义，尚未实现

这是 Z30 用户照片与视频目录的唯一详细说明。当前实现仍只处理照片；下面是目标行为。入口沿用 `docs/design/pages/06-photos.html`，本轮不改页面稿。

## 官方格式范围

- JPEG-Baseline 静态照片，扩展名 `.JPG`；Fine、Normal、Basic 是质量等级。
- NEF RAW 静态照片，扩展名 `.NEF`；12-bit、14-bit 是位深。
- MOV 视频，扩展名 `.MOV`。
- MP4 视频，扩展名 `.MP4`。

NEF+JPEG 是一次快门生成一个 NEF 和一个 JPG 的模式，不是第三种类型或筛选项。JPG 与 JPEG 不拆分。`.NDF` 是 Image Dust Off 除尘参考数据，不是用户媒体；它是目录唯一明确排除的 Z30 文件类型。

官方来源：

- [Z30 Specifications](https://onlinemanual.nikonimglib.com/z30/en/15-08.html)：NEF、JPEG-Baseline、NEF+JPEG、MOV、MP4。
- [File naming](https://onlinemanual.nikonimglib.com/z30/en/09-03-03.html)：NEF、JPG、MOV、MP4、NDF 及 NDF 用途。
- [Still images](https://onlinemanual.nikonimglib.com/z30/en/07-02.html) 与 [RAW recording](https://onlinemanual.nikonimglib.com/z30/en/09-03-08.html)。
- [Video file type](https://onlinemanual.nikonimglib.com/z30/en/09-04-07.html)：MOV、MP4。

## 日期分开的连续时间线

日期分组始终开启，不提供日期筛选。日期标题左对齐，同一天照片和视频自动归组并连续滚动。未知日期进入「日期未记录」。最早/最新同时决定组与组内顺序。

PTP capture timestamp 是没有时区的相机墙上时间。目录保留原始本地日期时间，并直接派生稳定 `LocalDate` 键。当前 `PtpCatalog.parseCaptureMillis` 强制按 UTC 转 epoch，午夜附近可能分错日期；分组键不得随手机时区、重启或重读改变。

## 格式筛选

选项必须正好是 `全部`、`JPEG`、`RAW / NEF`、`MOV`、`MP4`。完整目录先排序，再筛选，再按日期分组。筛选只生成派生可见集合，不清空缩略图/视频封面缓存，不重读 PTP。NEF+JPEG 在全部中显示两个文件，在 JPEG 与 RAW / NEF 中各显示一个。

## 列表、预览、播放、选择和删除

- JPEG/NEF 使用相机缩略图并进入照片预览。
- MOV/MP4 优先使用相机缩略图或 poster frame；没有时显示明确视频占位。
- 支持的视频在应用内播放；不支持时提供系统播放器或明确说明。播放能力不影响列出、选择、传输或删除。
- 选择按 PTP handle 保存；格式切换隐藏的已选项继续保留。
- 预览/播放只遍历当前可见媒体，不经过日期标题或隐藏项。
- 成功删除后从完整目录、派生结果、选择和预览/播放序列移除；失败或受保护项保留。
- 日期标题是 Lazy 行，但分页必须按可见媒体位置或已加载媒体数触发。

## 原文件传输与 MediaStore

JPEG、NEF、MOV、MP4 共用可靠 `TransferQueue`。原文件用 `GetPartialObject` 分块流到临时文件，并按安全偏移暂停、恢复和断点续传；尤其禁止把完整视频放进内存。校验完成前不得提交 MediaStore。

- JPEG/NEF 写 `MediaStore.Images`。
- MOV 写 `MediaStore.Video`，MIME `video/quicktime`。
- MP4 写 `MediaStore.Video`，MIME `video/mp4`。

保存保留文件名、原始字节和相机拍摄时间，不转码、不压缩。去重至少使用文件名、大小、本地拍摄时间、格式和目标 collection。前台服务只延续人已启动且仍在进行的手动传输。

## 验收

- 午夜边界和手机时区变化不改变相机本地日期组；未知日期进入「日期未记录」。
- `.JPG`、`.NEF`、`.MOV`、`.MP4` 和 NEF+JPEG 进入正确筛选；只排除 NDF。
- 格式切换不重读目录、不清缓存，选择按 handle 保留，预览/播放只遍历可见结果。
- 日期标题存在时分页仍按可见媒体位置触发。
- 视频中断后按安全偏移恢复，完成前不进 MediaStore，完成后可播放或得到明确回退。
