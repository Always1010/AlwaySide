# iPad 最小运行诊断

`AlwaySideDiagnostics.swiftpm` 是供 iPad Swift Playground 导入、运行的最小 SwiftUI App 项目。它用于回答：我们提供的项目能否在这台 iPad 上打开并运行，以及运行时报告的系统版本、Bundle 构建信息和编译期模块探测结果是什么。

**这是待用户真机验收的项目。当前仅在 Windows 做过静态结构检查，没有在 Swift Playground 4.7、Swift 编译器或 iPad 真机上验证。**

## 打开与运行

1. 将交付的 `AlwaySideDiagnostics.zip` 保存到 iPad 的“文件”App 中。
2. 在“文件”App 中轻点 ZIP 尝试解压；解压结果应包含完整的 `AlwaySideDiagnostics.swiftpm` 项目。不要只提取 `Package.swift`，也不要给项目另加 `.txt` 后缀。
3. 尝试轻点这个 `.swiftpm` 项目，选择用 Swift Playground 打开。若系统没有直接关联，可以尝试“共享”菜单中是否提供 Swift Playground，或在 Swift Playground 的文稿浏览界面查找该项目。具体入口及中文按钮名称尚未在 4.7 真机核实，以上是打开方式的尝试路径。
4. 成功进入项目后，使用 Swift Playground 的运行控件（Run / 播放图标），等待 App 显示“iPad 运行诊断”页面。
5. 点击右上角“复制诊断文本”（复制图标），然后将文本粘贴到聊天中。界面也支持选取字段值。

如果系统将 `.swiftpm` 显示为普通文件夹，或无法打开、构建、运行，请停止在该步骤并回传：卡住的步骤、屏幕上的完整错误原文，以及 Swift Playground 的版本。截图也可以。不要将“没有打开成功”解读为已经证明某个采集框架不受支持。

项目不要求 Mac、远程构建机或 API key；没有填写签名团队。这里的目标是在 Swift Playground 内运行；没有验证独立安装到主屏幕或发布流程。

## 诊断内容与边界

- 设备系统：公开的 `UIDevice` 系统名称、系统版本、设备类别，以及 `ProcessInfo` 系统版本描述；不读设备名称、序列号或设备唯一标识。
- Bundle 构建信息：`CFBundleShortVersionString`、`CFBundleVersion`、`DTPlatformName`、`DTPlatformVersion`、`DTSDKName`、`DTSDKBuild`、`DTXcode`、`DTXcodeBuild`、`MinimumOSVersion`。字段不存在、为空或类型不支持时显示 `unknown`，不会填入推测值。
- 编译期探测：分别以 `#if canImport(ScreenCaptureKit)` 和 `#if canImport(ReplayKit)` 产生 `true` / `false`。源码没有导入或调用这两个采集框架。

`canImport` 只描述当前编译环境能否找到模块。`true` 不能证明对应 API 在当前 iPad 可调用，不能证明可以后台采集、运行广播扩展或实现完整的屏幕/音频采集；`false` 也不能单独证明其他开发环境中的能力。Bundle SDK 字段是构建元数据，不等于设备当前运行的系统版本。此 App 也不检测 Swift Playground 自身的版本。

本探针不访问网络，不执行屏幕或音频采集，不添加采集、后台权限或 App Extension，不使用私有 API。复制按钮只在手动点击后写入系统剪贴板。探针能运行仍不等于解决 Swift Playground 的 SDK、框架可用性或 App Extension 限制。

## 结构来源

Manifest 的 `swift-tools-version: 5.5`、`AppleProductTypes`、`.iOSApplication`、iOS 15.2 最低平台、`App` executable target、空 `capabilities` 和 AppIcon 目录格式参考 [Apple 官方 About Me 示例下载](https://developer.apple.com/tutorials/downloads/com.apple.sample-apps/AboutMe.zip) 内的原生 `Package.swift` 与 `AppIcon.appiconset/Contents.json`。本项目只面向 iPad；占位图标为自行生成的简单图形。没有整包复制示例代码或图像。
