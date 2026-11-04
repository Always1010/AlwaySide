# Android 工程说明

产品功能和使用入口见[根目录 README](../../README.md)。本工程为原生 Kotlin 单应用模块，界面使用平台 View / Activity，悬浮窗口由 WindowManager 管理，没有 Compose 或 AndroidX 运行时依赖。

## 固定工具链

| 项目 | 工程配置 |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 9.5.0，使用其内置 Kotlin 支持 |
| Gradle Wrapper | 9.6.0，发行包 SHA256 固定在 wrapper 配置 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 29 |
| Build-Tools | 36.0.0 |
| applicationId | com.alwayside.android |

版本号以 [app/build.gradle.kts](app/build.gradle.kts) 为准。Android Studio 不是命令行构建的必需条件；本机出包无需模拟器、NDK、手机驱动或连接手机。

## 当前工作区构建

在仓库根目录运行已有本机辅助脚本：

```powershell
python .local/android/build.py :app:assembleDebug :app:lintDebug
```

该脚本复用现有 JDK 17，仅为构建子进程指定环境变量；SDK、Android 工具状态、Gradle 缓存均位于仓库的 .local 下。它会生成已被 Git 忽略的 apps/android/local.properties。脚本包含本机绝对路径，不作为通用安装器；其他电脑需先准备工具链并配置对应路径。

完整 APK 输出：app/build/outputs/apk/debug/app-debug.apk（相对于本工程目录）。

已有标准工具链的环境也可以在本目录运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

先为当前终端配置 JDK 17、SDK 路径和所需缓存目录；不要依赖系统默认 Java 的版本。SDK 路径可以通过 local.properties 指定，Windows 冒号及反斜杠须遵循 Java properties 转义规则。

## 测试签名

调试签名仅用于本地测试，私钥不入库；覆盖升级需要使用同一签名。

覆盖升级必须保留同一私钥。新环境需要原测试签名才能覆盖已安装的包；重新生成同名文件不会恢复原签名。本机 build.py 在签名文件缺失时会生成新密钥，因此已有安装用户的环境必须先确认签名文件仍在。

## 定向验证

按实际改动选择测试类，例如请求与会话变化时：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.alwayside.android.ProviderProtocolTest --tests com.alwayside.android.ChatSessionTest
```

GeometryTest 保护裁剪坐标；WavTest 保护 WAV 封装；ProviderProtocolTest 保护地址、生成参数、流边界和取消；ChatSessionTest 保护旧回调隔离、配置与选文快照、历史转写和追问；InteractionTest 保护多击互斥、录音取消回调与分状态映射。测试不会调用真实 API。真实服务、KeyStore、悬浮输入、文字选择菜单和音源可录性需真机验证。

## 源码职责

以下类位于 app/src/main/java/com/alwayside/android/：

| 类 | 职责 |
| --- | --- |
| MainActivity / SettingsActivity / HistoryActivity / ChatActivity | 应用对话主页、分类设置、权限控制与原生聊天 |
| ProcessTextActivity | 系统 PROCESS_TEXT / SEND 纯文本入口与无悬浮权限回退 |
| CapturePermissionActivity | 悬浮动作按需申请音频／屏幕采集授权，成功后继续原动作，取消时恢复悬浮球 |
| AssistantService | 前台服务、小球手势、贴球菜单、三类独立小窗与采集 |
| InteractionPreferences / TapSequence | 外观与分状态动作、窗口记忆、多击及取消录音隔离 |
| ActiveConversationBridge | 在悬浮与应用内页面间移交活动会话与媒体所有权 |
| FrameSource / CropView / Geometry | 新画面获取、冻结选择、坐标映射 |
| PlaybackRecorder / Wav | 内部播放音频采集与 WAV 封装 |
| ChatSession | 本轮配置与证据快照、追问、取消、重试、转写复用 |
| AiProvider / ProviderProtocol | HTTPS、Responses SSE、文件转写与有界解析 |
| SettingsStore / HistoryStore / ConversationMediaStore | 加密凭据、预设、状态与原文历史、可选私有图片 |
| Contracts / MarkdownText / Ui / ProbeState | 数据契约、展示及不含媒体和密钥的运行诊断 |

开发计划、验收结果和交付记录只放仓库根 .local/android/。工程 README 描述使用和结构，不承担阶段进度记录。
