# 小米 / 一加锁屏运营商时钟

一个仅作用于 `com.android.systemui` 的 LSPosed 模块，将锁屏左上角的运营商文字实时替换为
`HH:mm:ss`。当前适配小米 17（HyperOS 3 / Android 16），并增加了一加 Ace 5 Pro /
ColorOS 的兼容识别与诊断日志。

作者：null993

## 使用

1. 构建并安装 APK。
2. 在 LSPosed 中启用模块，作用域只勾选“系统界面（com.android.systemui）”。
3. 重启手机；也可以强制停止 SystemUI，但这会短暂黑屏，不建议在未保存工作时操作。

## 构建

当前工程使用 Android Studio Quail 3 自带的 JDK 25、Android SDK 37 编译，
目标系统仍为 Android 16（targetSdk 36）：

```shell
gradlew.bat :app:assembleRelease
```

APK 位于 `app/build/outputs/apk/release/app-release-unsigned.apk`，正式使用前请签名。

## 兼容策略

HyperOS 各版本会混淆 SystemUI 类名，因此模块不绑定某个小米私有类，而是 Hook 稳定的
`TextView` API。根据小米 17 / HyperOS 3 的实机日志精确识别 `carrier_text`，并通过
`KeyguardManager`、可见区域和单一视图所有权保证只更新锁屏左上角的目标控件。

一加、OPPO 和 realme 设备使用独立规则识别 `carrier`、`operator`、`plmn` 等 ColorOS
资源或控件类名，同时排除控制中心、状态栏和分隔符。候选控件会以
`LockscreenCarrierClock: OnePlus candidate` 写入 LSPosed 日志，便于按具体固件继续收敛。
ColorOS 的锁屏运行在 `com.android.systemui:ui` 子进程，模块会只额外接入该 UI 进程，
不会注入截图或前台服务等其他 SystemUI 子进程。

在一加设备上，模块还会识别主界面状态栏与下拉控制中心顶部的小型时钟，并分别持续更新为
`HH:mm:ss`；锁屏/AOD 的大型系统时钟会被排除。模块保留 ColorOS 小时数字 `1` 的红色样式，
并使用等宽数字与固定测量宽度避免秒数变化时挤动旁边的日期。

模块同时在目标视图绘制前校准时间，因此熄屏一段时间再亮屏时，第一帧就是当前时间。

如特定 ROM 没有效果，请在 LSPosed 日志中确认出现
`LockscreenCarrierClock: hook installed in SystemUI`，然后用布局检查工具查出左上角控件的资源名，
补充到 `LockScreenClockHook.isCarrierView()` 的匹配列表。
