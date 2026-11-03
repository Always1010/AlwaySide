# AlwaySide iPad

个人自用的原生 SwiftUI 屏幕问答助手，目标为 iPadOS 27.0 及以上，工程与共享 scheme 位于 `AlwaySide.xcodeproj`。紧凑聊天界面优先保留输入与回答空间，固定画面以小缩略图显示，点击可展开核对。

**当前修订仍需 Xcode 27.1 编译和 iPad 真机验收。** Windows 静态检查不能证明快捷指令输入、系统窗口入口或真实 API 已运行成功。未签名 IPA 必须个人签名后才能安装，此项目不依赖 Swift Playground。

## 快速问答

1. 打开设置，选择供应商并保存自己的 API Key。除密钥外所有参数都有默认值；保存密钥与编辑设置不会进行网络验证。
2. 按下节配置「系统截屏 → AlwaySide 接收截图」快捷指令，并绑定到 AssistiveTouch 长按。截图入口不启动持续屏幕采集，也不从旧录屏缓存猜测当前画面。
3. 停留在目标页面长按小球，再从边缘手势唤回已有助手小窗，核对新缩略图后输入问题。照片按钮仍可单选导入图片；不读取整个相册。每次新截图开始新会话、取消旧请求并清空旧图和当前追问；导入失败会明确显示错误，不回退旧图。
4. 输入问题后发送；输入为空则发送设置中的默认提问。**点击发送即表达本次上传意图**，不再逐次勾选确认。唤起、预览与编辑本身不调用 API。
5. 回答流式显示，支持 Markdown、代码复制和可点击来源；实际检索状态显示在回答下方。追问沿用当前图片和有界文字上下文。历史菜单可搜索、恢复或删除本机会话，并显示更新日期和时间；新会话按钮清空当前编辑状态。缩略图预览也显示接收日期和时间。
6. 停止按钮取消本次请求；发送、取消、导入和会话切换都会按各自状态更新。截图入口只传图片，不自动提问、不上传图片。

音频是独立的显式操作：系统音频录制需系统选择器授权，备用麦克风需麦克风权限；每次录制最多 2 分钟，也可导入不超过 20 MiB 的音频。录音结束后由用户选择上传至 OpenAI `gpt-transcribe` 转写，编辑后作为资料附加到聊天，最多 20,000 字，不挤占 2,000 字的问题输入。附加时已有回答会新建会话并保留当前图片；点击发送才把转写资料、当前图片与问题发给所选问答供应商。截图快捷指令与音频录制互不依赖。受保护内容或系统共享限制可能导致无音频；本修订没有视频片段分析。

## 系统入口：Slide Over 与 AssistiveTouch

应用提供适合窄窗口的聊天界面，**窗口位置、大小、置顶和恢复方式由 iPadOS 决定**。代码不能强制进入 Slide Over，也不能创建任意跨 App 常驻的自定义小球。原生「打开 App」入口已观察到全屏打开；不能把这条路径描述为已解决小窗恢复。

- **Slide Over**：由用户通过设备支持的系统多任务操作，把 AlwaySide 放入 Slide Over 并收至边缘，再用系统手势唤回。适用性与具体操作依当前系统多任务模式而定；本项目尚未完成该入口的真机验证。
- **推荐快捷指令**：依次添加系统「截屏」和 AlwaySide「接收截图（保持当前窗口）」两项动作。接收动作的「截图」参数必须连接前一步「截屏」的输出变量。移除旧的「打开 App」或「打开 URL」，不能先打开助手再截屏。
- **AssistiveTouch 绑定**：设置 → 辅助功能 → 触控 → AssistiveTouch → 自定操作 → 长按 → 选择上述快捷指令。更新 App 不会自动修改设备里已有的快捷指令或长按绑定；App 内「截图入口设置」页也提供这套步骤。
- **使用顺序**：目标页面长按小球，后台接收图片，然后从边缘手势唤回已有 Slide Over。默认接收动作只写本机图片收件箱，不要求前台激活；因此仍需一次手势。系统实际是否保持小窗、快捷指令权限及执行时限均需真机验收。
- **可选直接打开**：第二项可改为「接收截图并打开助手」，同样接收前一步截图。该动作请求前台激活，系统可能全屏，不能承诺恢复原来的小窗。
- **兼容链接**：`alwayside://ask` 仅打开会话并检查已收到的图片，不截屏。它不适合作为默认的「长按即截图」入口。

本轮代码修订未修改任何设备的 AssistiveTouch、快捷指令或多任务设置。应在两个不同目标页面分别截图，检查每次缩略图确实变化，再验证输入键盘、收起恢复及原应用操作。受保护内容无法绕过系统限制。PiP 不用于冒充可输入、可滚动和可点击来源的聊天窗。

## OpenAI 配置

默认型号为 `gpt-6-luna`，推理为低、搜索为自动、回答长度偏好为简短，输出与推理总预算为 32,768 tokens；默认应用指令与默认问题均可直接使用并编辑。目录也提供经官方能力核对的其他型号，账户是否有权限仍由服务端决定。高级型号 ID 可编辑；不认识或不兼容的型号会明确阻止发送，不静默换模型，不自动请求账户型号清单。

- 推理选项按目录能力标示，不支持的档位不会替用户静默转换。
- 搜索支持关闭、自动和必须联网。自动允许模型决定是否调用工具；必须联网发送实际工具约束，并根据工具结果检查是否完成。没有检索、检索失败、缺少来源，以及响应中断未拿到终态，会如实显示，不能把提示词或模型自述当作已经核验。
- 回答长度偏好与输出及推理总预算分开。高推理可能消耗较多预算并等待更久；预算也可能在可见回答生成前耗尽。
- 应用指令全文可编辑、复制、恢复默认，并可新建、应用、更新和删除本机预设，最多 20 套。设置中的全文就是实际发送的 OpenAI 应用指令，不暗中追加与其冲突的旧规则。
- 默认提问独立编辑，两家供应商共用。OpenAI 型号、推理、联网和应用指令设置仅用于 OpenAI；DeepSeek 继续使用既有 `deepseek-flash` 接口、关闭思考和原有请求行为。
- 请求开始时固定供应商、密钥、图片、问题、追问和配置值。参数编辑只影响后续请求；正在请求时不能切换供应商。旧请求的迟到片段不会写入新问题。

## 数据、缓存与生命周期

密钥分别保存在本机 `WhenUnlockedThisDeviceOnly` Keychain，不写入 UserDefaults、源码或日志，也不同步 iCloud。供应商与配置本地保存。图片收件箱与历史会话存于 App 的 Application Support，设置文件保护并排除备份；历史可在 App 内删除，无自建服务器或账户同步。

请求直连供应商官方 HTTPS API，使用一次性 ephemeral URLSession，拒绝重定向，关闭 Cookie 和响应缓存。OpenAI 使用 Responses API、`store=false` 与实际 `web_search` 工具，DeepSeek 使用 Chat Completions。失败后由用户决定是否重试，不自动更换供应商。API 与工具用量以及数据保留由本人供应商账户政策决定。取消与本机清空不能撤回已发送数据，也不保证免计用量。

每张发送图片不超过 3 MiB；截图或照片原文件不超过 20 MiB，缩小后最长边为 1280 像素。快捷指令先取得系统 PNG 表示，再校验尺寸并缩小；只保留最新一项原子收件箱，JSON 封装上限 5 MiB，超过 10 分钟的待处理截图会显示过期错误。接收失败会替换待处理旧图为失败记录。下次 scene 激活、主窗口取得焦点或前台接收通知时消费；成功与失败均清理对应收件箱，导入期间收到更新时保留更新项。无 Share extension、App Group 或额外签名能力。

当前提问不再使用唤起时间推断来源画面的旧逻辑。快捷指令明确传入图片，但助手仍不能认证来源 App；时间表示接收或导入时间。预览按固定图 ID 降采样缓存，流式文字更新不会反复解码所有图片。本地历史最多 30 个会话、每会话 60 轮；达到上限会明确阻止继续发送，请手动删除旧会话或新建对话，应用不自动删除原有记录。模型追问只携带最近 3 轮完整回答，非完整回答不纳入后续上下文。

已发送请求提前申请系统有限后台执行时间，收起后尽力完成并保留回答；后台时间到期会取消并结束任务，不无限持续联网。`screen-capture` 后台模式用于用户授权的系统音频采集，不提供无限网络权限。待消费截图和已保存历史可跨进程恢复；尚未保存的流式片段与编辑状态不保证恢复。

## 工程验证

单 Application target 使用 SwiftUI、UIKit、AppIntents、ScreenCaptureKit、AVFoundation、ImageIO、PhotosUI、Security 等系统框架，无第三方依赖。最低系统为 27.0、仅 iPad，当前 build 为 6，Debug/Release 共用 `Config/Base.xcconfig`，无额外开发团队、证书或付费服务配置。

在具有 Xcode 27.1 的 Apple 构建环境中，可进行不签名的编译检查：

```sh
xcodebuild -project apps/ipad/AlwaySide.xcodeproj \
  -scheme AlwaySide -configuration Debug \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

离线契约检查覆盖请求参数、工具约束、SSE 文本/搜索/引用终态、断流与大小边界，无需 API Key，也不发网络请求。在同一 Apple 工具链上单独运行：

```sh
mkdir -p .local
for source in apps/ipad/AlwaySide/Capture/CapturedFrame.swift \
  apps/ipad/AlwaySide/Models/OpenAISettings.swift \
  apps/ipad/AlwaySide/Models/Provider.swift \
  apps/ipad/AlwaySide/Services/VisionClient.swift \
  apps/ipad/AlwaySide/Services/AudioTranscriptionClient.swift \
  tests/OpenAIContractChecks.swift; do
  cat "$source"
  printf '\n'
done > .local/OpenAIContractChecks.swift
xcrun --sdk macosx swiftc -swift-version 5 -strict-concurrency=targeted -parse-as-library \
  .local/OpenAIContractChecks.swift -o .local/OpenAIContractChecks
.local/OpenAIContractChecks
```

CI 在 archive 前执行同一请求契约检查，并运行本机会话持久化、图片恢复、搜索和容量边界的离线检查。当前 Windows 没有运行上述 Apple 工具链命令，也未调用付费 API。静态检查只覆盖结构与代码状态边界。真机还应覆盖两个不同页面先后截图、后台接收后手势唤回、可选直接打开、无效及过期图片、无外接键盘、旋转、音频授权与静音来源、停止及中断、密钥锁定、配置切换、真实流式回答、工具约束、引用与取消。只有成功云构建才提供对应未签名产物；签名安装与运行另需验收。

## 测试构建

推送 `ipad-test-YYYYMMDD-NNN` 格式的 Git tag 会触发未签名构建，例如 `ipad-test-20261103-001`。日期后序号每天从 `001` 递增；每次修改使用新 tag，保留旧 tag，不移动或删除，也不创建 GitHub Release。构建固定检出该次事件的 commit，不跟随之后的分支更新。普通分支推送和 PR 不触发此工作流，仍可手动运行 `workflow_dispatch`。

工作流使用一个标准 `xcode-27` runner，无代码签名。成功后保留 unsigned IPA 与 SHA-256 校验文件的小型 artifact，IPA 上限 50 MiB、留存 1 天。构建步骤和资源限制以仓库当前工作流为准。本次实施不自动 push、tag 或触发 CI。

官方依据：[Apple App Intents 执行模式](https://developer.apple.com/documentation/appintents/configuring-the-runtime-behavior-of-your-app-intents)、[IntentFile](https://developer.apple.com/documentation/appintents/intentfile)、[前台模式](https://developer.apple.com/documentation/appintents/intentmodes/foregroundmode)、[Apple iPad 多任务与 Slide Over](https://support.apple.com/en-ca/125309)、[Apple AssistiveTouch](https://support.apple.com/en-ie/111794)、[iOS ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios)、[UIWindow key 通知](https://developer.apple.com/documentation/uikit/uiwindow/didbecomekeynotification)、[有限后台执行](https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:))、[OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision)、[OpenAI 网页搜索](https://developers.openai.com/api/docs/guides/tools-web-search)、[OpenAI 流式响应](https://developers.openai.com/api/docs/guides/streaming-responses)、[DeepSeek Vision](https://api-docs.deepseek.com/guides/vision/)。
