# Android 工程说明

本文面向构建和维护应用的开发者。产品介绍见[根目录 README](../../README.md)，操作方法见[使用说明](../../docs/usage.md)。

工程使用 Kotlin、Android View / Activity 和 Material Components，应用模块位于 `app/`。

## 构建准备

- JDK 17。
- Android SDK Platform 36 和 Build-Tools 36.0.0。
- 使用仓库自带的 Gradle Wrapper；Gradle、插件和依赖版本以工程配置为准。

在本目录创建不纳入 Git 的 `local.properties`，填写自己的 SDK 路径，或通过 `ANDROID_HOME` 指定已安装的 SDK。应用版本及最低系统要求见 [app/build.gradle.kts](app/build.gradle.kts)。

调试构建使用自定义签名。首次构建前，请在 `app/build.gradle.kts` 的 `signingConfigs` 中改用自己的调试密钥，或移除自定义配置以使用 Android 默认调试签名。覆盖安装需要相同包名，以及相同签名或有效的签名轮换授权链；请妥善保管私钥，不要将私钥或正式签名密码提交到仓库。

## 构建 APK

在 `apps/android/` 目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux 使用 `./gradlew`。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 一键打包并交付

在仓库根目录运行，传入版本号。Linux / macOS：

```sh
./package-apk.sh 1.4.1
```

Windows：

```powershell
.\package-apk.bat 1.4.1
```

两个入口都优先调用被 Git 忽略的 `.local/android/package-release-upload.py`。本地调用脚本负责配置当前进程环境，再调用通用的 `tools/package_apk.py`；未提供本地调用脚本时，入口直接使用环境变量或命令参数。真实路径、共享地址和签名身份只能保存在本地，不得写入受跟踪文件。

| 配置 | 环境变量或命令参数 |
| --- | --- |
| 已有 JDK | `JAVA_HOME` / `--java-home`，未指定时从 PATH 查找 Java |
| 已有 Android SDK | `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `--sdk` |
| 交付目录 | `ALWAYSIDE_APK_SHARE` / `--dest`，必须显式配置 |
| 正式密钥文件 | `ANDROID_KEYSTORE_PATH` / `--keystore` |
| 签名轮换链 | `ANDROID_SIGNING_LINEAGE_PATH` / `--lineage` |
| 预期证书 SHA-256 | `ALWAYSIDE_RELEASE_CERTIFICATE_SHA256` / `--certificate-sha256` |
| 可选的本地凭据 JSON | `ALWAYSIDE_SIGNING_CREDENTIALS` / `--credentials` |
| 构建缓存 | `GRADLE_USER_HOME` / `--gradle-user-home` |

签名密码与别名使用 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；也可由本地凭据 JSON 提供同名字段，环境变量优先。密钥和凭据不进入版本控制或交付目录。

需要已有 Python 3.10+、JDK 17 或以上，以及适合宿主系统与 CPU 架构的 SDK 原生工具。SDK、交付位置、密钥、轮换链和预期证书均无私人环境默认值；缺少配置时立即报错。可用 `--aapt2` 指定已有原生 AAPT2，脚本会将其传给 Gradle。脚本不安装工具或修改系统环境配置。

只检查现有配置和工具：

```sh
./package-apk.sh 1.4.1 --check
```

脚本计算与 GitHub Release 相同的 `versionCode`，默认离线构建 Release APK，附带原签名轮换链，并校验正式证书、V3 签名、包名和版本。只有校验通过后才复制到配置的交付目录，并核对 SHA-256；失败即停止，不交付旧包或覆盖同名文件。本地产物及交付记录保存在被忽略的 `.local/android-build/`。

默认缓存位于被忽略的 `.local/gradle-cache/`；离线模式要求 Wrapper 发行包及依赖缓存完整。准备好联网获取工程依赖时，可显式添加 `--online`。打包不额外运行单元测试或完整 Lint，Release 构建保留 Gradle 默认的 `lintVitalRelease`。其他参数见 `./package-apk.sh --help`。

## GitHub Release 自动发包

[Android Release 工作流](../../.github/workflows/release.yml) 在向 GitHub 推送 `v` 开头的 tag 时运行，也可在 Actions 页面通过 **Run workflow** 填写已有的 `release_tag` 手动构建；普通分支推送不会发包。发布 tag 必须采用 `v主版本.次版本.修订号`，例如 `v0.6.1`；每段为 0–999 的整数，不允许前导零、预发布后缀或 `v0.0.0`。不符合格式时工作流会报错并停止。

工作流使用 JDK 17 和工程指定的 Android SDK，运行 `lintRelease`、构建并验证签名 APK，然后创建同名 GitHub Release、自动生成发布说明，并上传：

- `AlwaySide-v0.6.1.apk`：可安装的 Release APK。
- `AlwaySide-v0.6.1.apk.sha256`：APK 的 SHA-256 校验文件。

### 首次配置签名

在 GitHub 仓库的 **Settings → Secrets and variables → Actions → Repository secrets** 中配置以下 Secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 签名 keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |
| `ANDROID_SIGNING_LINEAGE_BASE64` | 可选，旧签名到当前签名的轮换授权链（`apksigner rotate` 输出文件的 Base64 内容） |

使用计划长期保留的签名密钥。若需要覆盖已安装的测试包，应沿用原签名，或提供由旧私钥授权新证书的签名轮换链。直接换成无授权关系的新签名将无法覆盖安装；卸载旧包会清除应用本地数据。工作流不会生成临时签名，也不会将私钥上传到 Release；必需的 Secrets 缺失或签名校验失败时停止发包。

配置轮换链后，工作流会使用当前私钥重新签署最终 APK，携带 V3 轮换证明，并将轮换最低 API 设为 28。项目最低 API 为 29，因此无需用旧私钥生成 V1/V2 兼容签名，旧私钥也无需上传 GitHub。生成轮换链时须保留旧证书的 `installed-data` 能力，以支持保留应用数据的升级；后续版本必须继续携带这条链。密钥和轮换链都应备份，覆盖安装及数据保留仍需在目标设备上验收。

在 PowerShell 中可用以下命令将现有 keystore 转成 Base64 并复制到剪贴板，将路径替换成自己的文件位置：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'release.jks').Path)) | Set-Clipboard
```

创建 Release 使用工作流自带的 `GITHUB_TOKEN` 和 `contents: write` 权限，无需额外配置个人访问令牌；仓库或组织策略需要允许该权限。

### 版本与触发方式

APK 的 `versionName` 自动取 tag 去掉 `v` 后的值，`versionCode` 按 `主版本 × 1000000 + 次版本 × 1000 + 修订号` 生成。例如 `v0.6.1` 对应 `0.6.1` 和 `6001`，`v1.0.0` 对应 `1.0.0` 和 `1000000`。后续发布应递增版本，覆盖安装要求 `versionCode` 高于旧包。本地不传版本参数时仍使用 `app/build.gradle.kts` 中的默认版本。

确认工作流所在提交已推送到 GitHub、签名 Secrets 已配置后，再执行下面的命令发布新版本（本次配置工作流不需要执行）：

```powershell
git tag -a v0.6.1 -m "发布 v0.6.1"
git push origin v0.6.1
```

自动触发时，tag 必须指向包含工作流及待发布代码的提交。手动触发时可选择包含修复后工作流的分支，再填写已有 tag；检出的应用源码始终来自该 tag，无需移动 tag 即可修复发布流程或签名配置后重建。发布后在仓库 Actions 和 Releases 查看结果。配置问题修复后可重跑原任务；工作流本身修复后应从包含修复的分支手动触发。已存在的 Release 不会被覆盖，需要为新内容使用新版本 tag。

本地需要签名 Release 时，设置 `ANDROID_KEYSTORE_PATH`（绝对路径）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD` 环境变量，再运行 `:app:assembleRelease`；可通过 `"-PreleaseVersionName=0.6.1" "-PreleaseVersionCode=6001"` 指定版本。未设置签名路径的本地 Release 构建会生成未签名 APK，不能直接安装。

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

文档图片需要更新时，可手动运行[原生文档素材工具](../../tools/doc_media/README.md)。它是单向复用展示组件的独立工程，不参与本 App 的构建或运行；预览先输出到 `.local/`，人工确认后再导出。

源码位于 [app/src/main/java/com/alwayside/android/](app/src/main/java/com/alwayside/android/)，测试位于 [app/src/test/](app/src/test/)。

| 范围 | 主要入口 |
| --- | --- |
| 应用页面与导航 | `MainActivity`、`AppNavigation`、`SettingsScreen`、`ChatScreen` |
| 悬浮交互与采集 | `AssistantService`、`FrameSource`、`PlaybackRecorder` |
| 请求与会话 | `AiProvider`、`HttpTransport`、四种 `ChatAdapter`、`ChatSession` |
| 服务与协议 | `ProviderConnections`、`ConnectionConfigCodec`、`ProviderCapabilities`；格式详见 [API 服务商与接口格式](../../docs/api-providers.md) |
| 设置与历史 | `SettingsStore`、`HistoryStore`、`ConversationMediaStore` |

界面维护参考[界面设计说明](../../docs/ui-design.md)，协作和提交遵循 [AGENTS.md](../../AGENTS.md)。本地计划、验收及构建记录统一放在仓库根目录 `.local/`，不随仓库分发。
