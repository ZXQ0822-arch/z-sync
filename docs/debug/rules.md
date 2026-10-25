# 调试记录铁律

调试时按这一份做。规则放在 `docs/debug/` 最外层。每一次调试的材料放进以版本号命名的子目录，例如 `docs/debug/0.1.22/`。不要把截图、日志、对话堆在最外层。新的一次调试、新的安装版本，另开一个版本目录，不要混进上一个版本。

## 1. 对话

`conversation.md` 必须如实记录用户和助手的全部对话。用户每说一条，立刻写入，不要攒着，不要拖到下一轮再补。

这份文件里分两层：

- 第一层：对话原话，按时间记下来。
- 第二层：从对话里整理出的问题，和对应的描述。

只做记录。不要为了写这份记录去读代码、做分析。

## 2. 截图

用户让截图时才截图，不要自己连续截。文件名带时间，或带页面名字。在 `conversation.md` 里写到这个问题时，用链接指向这张图。

## 3. 日志

手机日志持续全量写入该版本目录下的日志文件，不等到有人要求读才写。不要只留摘要，把原文放进去。在 `conversation.md` 里指向这份日志。

`adb` 不进仓库，自己下 platform-tools 放到 `tools/adb/`，用 `tools/adb/adb`。

## 日志采集

目录始终用当前正在测的版本，写成 `docs/debug/<当前版本>/`。不要把某一次的版本号写死当成以后的目标。下面的 `0.1.23` 只是举例。

例如版本 0.1.23 的测试已经结束。那一次的手机日志全量在 `docs/debug/0.1.23/phone-logs.txt`，从 2026-10-24 18:29:49 抓到 18:33:28。同目录的 `conversation.md` 是那一次测试的完整记录。

日志按当前版本目录落盘。例如版本 0.1.23 时，文件是 `docs/debug/0.1.23/phone-logs.txt`。抓的是整份 `logcat`，不是摘要。

### 持续全量

在仓库根目录执行。`VERSION` 换成当前正在测的版本。这条会一直写，直到停掉。

```bash
ADB=$PWD/tools/adb/adb
VERSION=0.1.23   # 举例。换成当前正在测的版本
OUT=$PWD/docs/debug/${VERSION}/phone-logs.txt
{
  echo "# capture started $(date '+%Y-%m-%d %H:%M:%S')"
  echo "# continuous full logcat for version ${VERSION}"
  "$ADB" logcat -v threadtime | python3 -u -c 'import sys
for line in sys.stdin:
    sys.stdout.write(line)
    sys.stdout.flush()'
} >> "$OUT"
```

`python3 -u` 是为了按行立刻写入，避免管道把日志攒在缓冲区里。

停掉这次抓取用的是结束对应的后台进程，没有清 logcat 缓冲。

### 截图命令

用户要求时才截。文件名带页面名或时间。下面路径里的 `0.1.23` 只是举例，应换成当前正在测的版本。例如版本 0.1.23 的两张是：

```bash
ADB=$PWD/tools/adb/adb
VERSION=0.1.23   # 举例。换成当前正在测的版本
"$ADB" exec-out screencap -p > "$PWD/docs/debug/${VERSION}/wifi-settings.png"
"$ADB" exec-out screencap -p > "$PWD/docs/debug/${VERSION}/phone-screen.png"
```

在该版本的 `conversation.md` 里用相对链接指向图片。
