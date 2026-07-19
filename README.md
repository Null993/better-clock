# 小米 17 / HyperOS 3 锁屏运营商时钟

一个仅作用于 `com.android.systemui` 的 LSPosed 模块，将锁屏左上角的运营商文字实时替换为 `HH:mm:ss`。

## 使用

1. 构建并安装 APK。
2. 在 LSPosed 中启用模块，作用域只勾选“系统界面（com.android.systemui）”。
3. 重启手机；也可以强制停止 SystemUI，但这会短暂黑屏，不建议在未保存工作时操作。

## 构建

需要 JDK 11、Android SDK 35：

```shell
gradlew.bat :app:assembleRelease
```

APK 位于 `app/build/outputs/apk/release/app-release-unsigned.apk`，正式使用前请签名。

## 兼容策略

HyperOS 各版本会混淆 SystemUI 类名，因此模块不绑定某个小米私有类，而是 Hook 稳定的
`TextView.setText`，通过资源名识别 `keyguard_carrier`、`carrier_text`、`operator_name`
等锁屏运营商控件，并通过 `KeyguardManager` 保证只在锁屏状态更新时间。

如特定 ROM 没有效果，请在 LSPosed 日志中确认出现
`MiuiLockscreenClock: hook installed in SystemUI`，然后用布局检查工具查出左上角控件的资源名，
补充到 `LockScreenClockHook.isCarrierView()` 的匹配列表。
