# Better Clock

一个仅作用于 `com.android.systemui` 的 LSPosed 模块，将锁屏左上角的运营商文字实时替换为
`HH:mm:ss`。当前适配小米 17（HyperOS 3 / Android 16）和一加 Ace 5 Pro（ColorOS 15 /
Android 15）。

作者：null993

## 功能

- 小米 / HyperOS：锁屏左上角运营商实时显示 `HH:mm:ss`。
- 一加 / ColorOS：锁屏运营商、桌面状态栏和控制中心时钟显示 `HH:mm:ss`。
- 保留 ColorOS 小时数字 `1` 的红色样式。
- 固定状态栏时钟占位宽度，避免秒数变化推动旁边的日期。
- 熄屏后亮屏及解锁过渡时立即校准时间。

## 使用

1. 从 [Releases](https://github.com/Null993/better-clock/releases) 下载并安装正式签名 APK。
2. 在 LSPosed 中启用 Better Clock。模块会将“系统界面（`com.android.systemui`）”标记为
   推荐作用域，只需勾选该应用。
3. 重启手机；也可以强制停止 SystemUI，但这会短暂黑屏，不建议在未保存工作时操作。

如果安装过包名为 `io.github.miuiclock` 的旧测试版，请先在 LSPosed 中停用旧模块；确认新版
`io.github.betterclock` 工作正常后即可卸载旧版。

一加设备使用 Zygisk Next 时，相关策略需要设为“仅还原挂载”，不能使用“强制”，否则
SystemUI 中的 LSPosed 注入会被还原。

## 构建

当前工程使用 Android Studio Quail 3 自带的 JDK 25、Android SDK 37 编译，
目标系统仍为 Android 16（targetSdk 36）：

```shell
gradlew.bat :app:assembleRelease
```

正式签名构建需要在项目根目录创建未纳入 Git 的 `keystore.properties`：

```properties
storeFile=signing/better-clock-release.jks
storePassword=你的密钥库密码
keyAlias=better-clock
keyPassword=你的密钥密码
```

配置签名后，APK 位于 `app/build/outputs/apk/release/app-release.apk`。未配置签名时 Gradle
仍可生成 unsigned Release，但不能直接安装或作为正式版本发布。

本模块使用 legacy Xposed API，因此推荐作用域由 Manifest 中的 `xposedscope` 元数据声明；
`META-INF/xposed/scope.list` 仅作为未来迁移到 modern Xposed API 时的同源声明，不能代替
legacy 元数据。

## 兼容策略

HyperOS 各版本会混淆 SystemUI 类名，因此模块不绑定某个小米私有类，而是 Hook 稳定的
`TextView` API。根据小米 17 / HyperOS 3 的实机日志精确识别 `carrier_text`，并通过
`KeyguardManager`、可见区域和单一视图所有权保证只更新锁屏左上角的目标控件。

一加、OPPO 和 realme 设备使用独立规则识别锁屏运营商、桌面状态栏时钟和控制中心时钟，
同时排除无关的分隔符及大型锁屏/AOD 时钟。候选控件会以
`LockscreenCarrierClock: OnePlus candidate` 写入 LSPosed 日志，便于按具体固件继续收敛。
ColorOS 的锁屏运行在 `com.android.systemui:ui` 子进程，模块会只额外接入该 UI 进程，
不会注入截图或前台服务等其他 SystemUI 子进程。

在一加设备上，模块还会识别主界面状态栏与下拉控制中心顶部的小型时钟，并分别持续更新为
`HH:mm:ss`；锁屏/AOD 的大型系统时钟会被排除。模块保留 ColorOS 小时数字 `1` 的红色样式，
并使用固定测量宽度避免秒数变化时挤动旁边的日期，同时保留系统原生数字间距。

模块同时在目标视图绘制前校准时间，因此熄屏一段时间再亮屏时，第一帧就是当前时间。

如特定 ROM 没有效果，请在 LSPosed 日志中确认出现
`LockscreenCarrierClock: hook installed in SystemUI`，然后用布局检查工具查出左上角控件的资源名，
补充到 `LockScreenClockHook.isCarrierView()` 的匹配列表。
