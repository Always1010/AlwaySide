# AlwaySide iPad

适合放在其他应用旁边的小窗口阅读助手，使用原生 SwiftUI，目标 iPadOS 27.0+。用户手动安排窗口；应用不再提供悬浮球、长按截图、快捷指令或自动恢复/固定 Slide Over 的入口。

当前源码包含主 App 和 TranslationUIProvider 翻译扩展两个 target，共用 `AlwaySide.xcodeproj` 与 `AlwaySide` scheme。**此次改造尚未经过 Xcode 27.1 编译、签名安装或 iPad 真机验证。** Windows 结构检查不能替代这些验证。

## 窗口内使用

首页保留两个主要按钮和一个结果对话区。窄窗口自动把按钮纵向排列；API 配置、历史和导入放在次要入口。

1. 手动将 AlwaySide 放在阅读或视频窗口旁边，在更多菜单的设置中保存自己的 API Key。
2. 点击 **获取当前界面**，在系统选择器授权共享。应用等到一张新画面后停止共享，并按默认提问上传分析。后续追问沿用这张图片；再次获取画面会新建上下文，不自动带入上一次截图。
3. 点击 **开始监听**，通过系统选择器收录设备音频。点击 **结束监听** 停止本段并自动转写、翻译和解释，结果直接进入同一个对话区。
4. 监听每段最多 120 秒；到上限只在本机停止，点击 **解释本段** 才上传。处理失败时保留本地音频和原文，重试解释复用已有转写；处理或授权期间可取消，放弃本段会清理临时音频。
5. 更多菜单保留图片导入、麦克风监听和音频文件导入。导入图片后直接分析；选择音频文件后直接转写解释。设备音频、麦克风和音频文件目前使用 OpenAI；DeepSeek 继续保留图片问答。
6. 结果可复制、继续追问或从历史重新打开。获取画面和开始/结束监听不会强制弹出键盘。

截图、录音、导入和 API 处理采用互斥主操作，避免同一个系统共享选择器被多个任务抢占。失败后保留录音时仍可进入设置修正密钥或参数，不必丢弃录音。后台只使用系统允许的有限处理时间；不承诺无限后台联网。

## 截图范围与限制

默认请求整个屏幕。更多菜单还提供 **指定窗口（试验）**，通过 `SCContentSharingPicker.present(using:)` 请求 `.window`；`.application` 已被 Xcode 27.1 的实际 iOS SDK 判定不可用，因此不提供指定应用选项。

这些是系统授权的共享路径，不是静默读取其他应用。实际可选来源、是否包含助手小窗口、受保护内容和音频权限必须在目标 iPad 验证。不会使用本应用截图冒充其他应用画面，也不通过唤起前缓存猜测来源；取得新帧之前取消或失败，不发送旧图片。图片时间是接收时间，不能认证来源应用。

当前实现使用 ScreenCaptureKit，不新增 ReplayKit 广播扩展。iPad 不使用 macOS 专属的 `allowedPickerModes` 或应用枚举接口。

官方依据：[iOS 屏幕采集](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios)、[系统选择器内容类型](https://developer.apple.com/documentation/screencapturekit/sccontentsharingpicker/present(using:))。

## 跨应用选中文字翻译

新增 `AlwaySideTranslation` 系统翻译扩展，参考欧路词典的轻量卡片：顶部关闭/展开、原文折叠、译文与解释切换、复制；宿主允许替换时提供译文替换。

正确签名安装后，需由用户在系统的默认翻译 App 设置中选择 AlwaySide。在支持系统文字翻译的其他应用中，选中文字并使用 **翻译**，由系统展示扩展；不会给所有应用强行插入一个自定义菜单按钮，也不接管系统 Look Up。

使用系统翻译入口会将所选文字发送至 OpenAI，切换解释时按需发起解释请求。打开和展开已生成结果不重复请求；失败由用户点击重试。关闭、离开或改变原文会取消旧请求，迟到结果不能覆盖新选择。所选文字最多 20,000 字，译文目标暂为简体中文；不显示尚未实现的语言选择功能。卡片结果暂不存聊天历史。

扩展复用主 App 的 OpenAI 密钥、型号、推理、搜索及应用指令；主 App 的设置额外同步到共享 Keychain。主 App 与扩展都声明相同的 `keychain-access-groups`，密钥仍为 `WhenUnlockedThisDeviceOnly`，不存明文文件、不使用 App Groups 或 iCloud 同步。主 App 保留原有 Keychain service/account，默认 group 与新共享 group 一致时可以沿用已有密钥。

签名时主 App 与扩展必须使用匹配的 App Identifier Prefix 和允许共享 Keychain 的 provisioning；主 App 还需要 `com.apple.developer.translation-app` 能力及翻译扩展联网声明。工程仅声明这些能力，本次未改动开发者账户、证书或本机环境。未签名 archive 不能证明设备签名会接受这些能力，后续重签也必须保留嵌入扩展和必要 entitlements。

扩展使用 ExtensionKit product type，嵌入 App 的 `Extensions` 目录，不是传统 `PlugIns`。注册 identifier 使用 `com.apple.public.translation-ui-provider`，最终注册与默认翻译选择需真机验收。

官方依据：[默认翻译 App 配置](https://developer.apple.com/documentation/translationuiprovider/preparing-your-app-to-be-the-default-translation-app)、[翻译上下文](https://developer.apple.com/documentation/translationuiprovider/translationuiprovidercontext)、[共享 Keychain](https://developer.apple.com/documentation/xcode/configuring-keychain-sharing)。

## OpenAI 配置

默认型号为 `gpt-6-luna`，推理为低、搜索为自动、回答长度偏好为简短，输出与推理总预算为 32,768 tokens；默认应用指令与默认问题均可直接使用并编辑。目录也提供经官方能力核对的其他型号，账户是否有权限仍由服务端决定。高级型号 ID 可编辑；不认识或不兼容的型号会明确阻止发送，不静默换模型，不自动请求账户型号清单。

- 推理选项按目录能力标示，不支持的档位不会替用户静默转换。
- 搜索支持关闭、自动和必须联网。自动允许模型决定是否调用工具；必须联网发送实际工具约束，并根据工具结果检查是否完成。没有检索、检索失败、缺少来源，以及响应中断未拿到终态，会如实显示，不能把提示词或模型自述当作已经核验。
- 回答长度偏好与输出及推理总预算分开。高推理可能消耗较多预算并等待更久；预算也可能在可见回答生成前耗尽。
- 应用指令全文可编辑、复制、恢复默认，并可新建、应用、更新和删除本机预设，最多 20 套。设置中的全文就是实际发送的 OpenAI 应用指令，不暗中追加与其冲突的旧规则。
- 默认提问独立编辑，两家供应商共用。OpenAI 型号、推理、联网和应用指令设置仅用于 OpenAI；DeepSeek 继续使用既有 `deepseek-flash` 接口、关闭思考和原有请求行为。
- 请求开始时固定供应商、密钥、图片、问题、追问和配置值。参数编辑只影响后续请求；正在请求时不能切换供应商。旧请求的迟到片段不会写入新问题。

## 数据与保留能力

API 仍直连供应商，OpenAI Responses 使用 `store=false`，保留流式回答、搜索状态、来源、取消及错误处理；DeepSeek 沿用图片请求。请求开始时固定配置，不自动重试、不自动更换模型。

聊天和图片存本机，保留最多 30 段会话、每段 60 轮，达到上限由用户决定删除或新建；追问携带最近 3 轮完整文字。截图、音频分别建立独立上下文。图片导入保留 20 MiB 文件边界、有界读取、缩小与完整 JPEG 校验；系统帧处理只保留最新一帧，停止后清空，不再保留唤起前候选图库。音频文件保留 20 MiB 输入边界、转写 20,000 字上限和临时文件清理。

旧的截图收件箱、快捷指令 snippets、App Intent、URL 唤起、窗口记忆/恢复及专用测试已移除。系统中已存在的个人快捷指令不会由此源码修改自动删除，但新版不再提供相应动作。

## 构建与验证

在 Xcode 27.1 环境可使用共享 scheme 编译主 App 与内嵌翻译扩展：

```sh
xcodebuild -project apps/ipad/AlwaySide.xcodeproj \
  -scheme AlwaySide -configuration Debug \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

现有离线契约保留 OpenAI 请求与流式响应、聊天持久化/搜索、真实 ImageIO 图片导入、音频转写解释的取消和重试。`.github/workflows/ipad-unsigned.yml` 保留这些检查并移除废弃阅读卡片检查。工作流支持从选定分支手动运行，也支持既有测试标签触发；构建固定到该次事件的 commit。打包包含主 App 与 `Extensions/AlwaySideTranslation.appex`，签名与真机验证另行完成。

真机验收至少覆盖：

- 小窗口内两个主按钮和对话区、旋转、大字号及键盘；无悬浮球/长按/窗口恢复设置。
- 整屏及指定窗口的系统授权、取消、超时、受保护内容、连续两次画面不同。
- 取消截图后立即开始监听、系统拒绝停止后的重试，确认共享资源不串用。
- 设备音频、麦克风、音频文件、两分钟上限、结束上传、取消、重试及中断后重新开始。
- 同一音频重试后取消，最新部分结果可见；密钥错误后保留本段并修改设置重试。
- 默认翻译注册、Safari 等应用选中文字、卡片翻译/解释/复制/关闭/替换、主扩展 Keychain 共享及锁屏恢复。
- API 真实流式响应、历史打开与删除、存储失败及后台时间耗尽。

本地需求、任务分工和验证结果统一存 `.local/`，不纳入 Git。
