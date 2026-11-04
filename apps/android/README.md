# Android 工程说明

本文面向构建和维护应用的开发者。产品介绍见[根目录 README](../../README.md)，操作方法见[使用说明](../../docs/usage.md)。

工程使用 Kotlin、Android View / Activity 和 Material Components，应用模块位于 `app/`。

## 构建准备

- JDK 17。
- Android SDK Platform 36 和 Build-Tools 36.0.0。
- 使用仓库自带的 Gradle Wrapper；Gradle、插件和依赖版本以工程配置为准。

在本目录创建不纳入 Git 的 `local.properties`，填写自己的 SDK 路径，或通过 `ANDROID_HOME` 指定已安装的 SDK。应用版本及最低系统要求见 [app/build.gradle.kts](app/build.gradle.kts)。

调试构建使用自定义签名。首次构建前，请在 `app/build.gradle.kts` 的 `signingConfigs` 中改用自己的调试密钥，或移除自定义配置以使用 Android 默认调试签名。覆盖安装需要相同包名和签名；请妥善保管私钥，不要将私钥或正式签名密码提交到仓库。

## 构建 APK

在 `apps/android/` 目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux 使用 `./gradlew`。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 验证改动

静态检查：

```powershell
.\gradlew.bat :app:lintDebug
```

按改动范围运行相关单元测试，例如请求协议或会话逻辑变化时：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.alwayside.android.ProviderProtocolTest --tests com.alwayside.android.ChatSessionTest
```

单元测试不调用真实 API。悬浮交互、系统采集授权、内部音频、文字选择入口及真实服务兼容性仍需在 Android 设备上验证。

## 代码与参考

源码位于 [app/src/main/java/com/alwayside/android/](app/src/main/java/com/alwayside/android/)，测试位于 [app/src/test/](app/src/test/)。

| 范围 | 主要入口 |
| --- | --- |
| 应用页面与导航 | `MainActivity`、`AppNavigation`、`SettingsScreen`、`ChatScreen` |
| 悬浮交互与采集 | `AssistantService`、`FrameSource`、`PlaybackRecorder` |
| 请求与会话 | `AiProvider`、`ProviderProtocol`、`ChatSession` |
| 设置与历史 | `SettingsStore`、`HistoryStore`、`ConversationMediaStore` |

界面维护参考[界面设计说明](../../docs/ui-design.md)，协作和提交遵循 [AGENTS.md](../../AGENTS.md)。本地计划、验收及构建记录统一放在仓库根目录 `.local/`，不随仓库分发。
