# 小米 17 / HyperOS 3 锁屏运营商时钟

一个仅作用于 `com.android.systemui` 的 LSPosed 模块，将锁屏左上角的运营商文字实时替换为 `HH:mm:ss`。

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

模块同时在目标视图绘制前校准时间，因此熄屏一段时间再亮屏时，第一帧就是当前时间。

如特定 ROM 没有效果，请在 LSPosed 日志中确认出现
`MiuiLockscreenClock: hook installed in SystemUI`，然后用布局检查工具查出左上角控件的资源名，
补充到 `LockScreenClockHook.isCarrierView()` 的匹配列表。
