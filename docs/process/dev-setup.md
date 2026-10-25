# 手机调试连接

人怎么配合相机，见 [开发循环](dev-loop.md)。这里只写 Mac 怎么连上手机、`adb` 放在哪、包怎么装。

`adb` 不进仓库（它是 platform-tools 里的二进制，十兆以上）。自己下一次 platform-tools，把 `adb` 放到仓库根的 `tools/adb/` 下，然后：

```bash
ADB=tools/adb/adb
"$ADB" devices -l
```

在仓库根目录执行。`tools/adb/` 已在 `.gitignore` 里。

已经装过 Android SDK 的话，直接用它自带的那个也行，例如：

```text
/opt/homebrew/share/android-commandlinetools/platform-tools/adb
```

读 APK 版本用的是同一套 SDK 里的 aapt，没有放进仓库：

```text
/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/aapt
```

## 小米 / MIUI / HyperOS：无线调试

手机和 Mac 必须在同一个 Wi-Fi。

1. 设置 → 我的设备 → 全部参数与信息，连点版本号，打开开发者选项。
2. 打开「USB 调试」。还有「USB 调试（安全设置）」或「USB 安装」也打开。
3. 开发者选项里打开「无线调试」。
4. 页面上有 IP 和端口。第一次要配对时，用配对码那一行的端口：

   ```bash
   "$ADB" pair 192.168.x.x:配对端口
   "$ADB" connect 192.168.x.x:调试端口
   ```

   已经配对过，只做 `connect`。`"$ADB" devices -l` 里这台是 `device` 才算接上。

## 连上相机热点之后

手机切进相机热点时会离开原来的 Wi-Fi，无线 `adb` 会断。

要让调试连着，用 USB 网络共享：数据线插上，手机打开 USB 网络共享。Mac 上会出现一块 USB 网卡。再用这块网上的手机地址去 `connect`，不要沿用家里 Wi-Fi 的地址。地址看 `ifconfig` 里新出现的网段。

一加和其他手机更稳的做法是直接用数据线，不走无线。插上后：

```bash
"$ADB" devices -l
```

列表里 `transport` 是 `usb` 即可。这次一加 9RT 就是这样认到的。

## 装到手机

不先卸载。用 `install -r` 盖上去。装之前和装之后对版本和 SHA-256，对不上就再装一次，不要让人重测。

1. 看 `androidApp/build.gradle.kts` 里的 `versionCode` 和 `versionName`。要装到手机的新包才把这两个数字加一。已经升过、还没装过的那一版，重编时不要再加。
2. 看现有 APK 是不是旧的。版本用 aapt；源码比 APK 新，就重编。

   ```bash
   AAPT=/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/aapt
   "$AAPT" dump badging androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep -E "package:|versionCode|versionName"
   find androidApp shared -name '*.kt' -newer androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

3. 源码更新，或 APK 不存在，就重编：

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

4. 装到当前这台 `device`：

   ```bash
   "$ADB" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

5. 核对手机上的包。两个 SHA-256 必须一样。

   ```bash
   "$ADB" shell dumpsys package app.zsync | grep -E "versionCode|versionName|lastUpdateTime"
   "$ADB" shell sha256sum "$("$ADB" shell pm path app.zsync | sed 's/package://' | tr -d '\r')"
   shasum -a 256 androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

包名是 `app.zsync`。连接页底部应显示 `v版本名 (versionCode)`。

## 这次装上的包

记录一次成功的流程：`./gradlew :androidApp:assembleDebug` 编译，`install -r` 安装，然后核对手机上的 `versionName`/`versionCode` 与本地产物的 SHA-256，两边一致才算装对。
