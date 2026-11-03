# AlwaySide iPad

个人自用的原生 SwiftUI 屏幕问答助手，目标为 iPadOS 27.0 及以上，工程与共享 scheme 位于 `AlwaySide.xcodeproj`。紧凑聊天界面优先保留输入与回答空间，固定画面以小缩略图显示，点击可展开核对。

**当前代码仍需 Xcode 27.1 编译和 iPad 真机验收。** Windows 静态检查不能证明系统窗口入口、自动取图或真实 API 已运行成功。未签名 IPA 必须个人签名后才能安装，此项目不依赖 Swift Playground。

## 快速问答

1. 打开设置，选择供应商并保存自己的 API Key。除密钥外所有参数都有默认值；保存密钥与编辑设置不会进行网络验证。
2. 开始系统共享，选择允许共享的范围。共享在本机接收有界画面缓存，不连续向模型上传。受保护内容或共享范围外的内容可能不可见。
3. 回到正在浏览的目标页面停留片刻，再唤起助手。应用尝试保留唤起前的共享画面，直接显示小缩略图，无需从长列表选图。展开可查看接收时间和完整预览；时间不是来源证明，画面可能包含系统过渡或窗口遮挡。取图失败不会静默使用最新的助手自身画面；按提示收起、回目标页面停留后重开，或用照片按钮导入一张截图作为备选。
4. 输入问题后发送；输入为空则发送设置中的默认提问。**点击发送即表达本次上传意图**，不再逐次勾选确认。唤起、预览与编辑本身不调用 API。
5. 回答流式显示，OpenAI 实际检索状态与可点击来源显示在回答下方。追问沿用同一张固定图片，最多携带 3 轮文字；重新唤起不会替换正在问的图。点击相机图标，才用本次唤起前画面另开上下文。没有新图或新图过期时会提示重新唤起。
6. 停止按钮取消本次请求；菜单中的“停止共享并清空”取消网络、释放采集并清空画面和追问。“清空画面与追问”保留共享，继续在本机接收新帧。系统停止共享或共享失败也会取消并清空当前证据。

照片输入只经系统 PhotosPicker 读取单选图片并缩小，不读取整个相册，不能证明跨应用共享成功。首版只处理固定图片与文字，不请求麦克风权限、不录音、不转写，也没有视频片段分析。

## 系统入口：Slide Over 与 AssistiveTouch

应用提供适合窄窗口的聊天界面，**窗口位置、大小、置顶和恢复方式由 iPadOS 决定**。代码不能强制进入 Slide Over，也不能创建任意跨 App 常驻的自定义小球。保留单场景、单会话，避免多个助手窗口相互覆盖上下文。

- **Slide Over**：由用户通过设备支持的系统多任务操作，把 AlwaySide 放入 Slide Over 并收至边缘，再用系统手势唤回。适用性与具体操作依当前系统多任务模式而定；本项目尚未完成该入口的真机验证。
- **系统 AssistiveTouch 小球**：可以作为入口探索方向。应用注册 `alwayside://ask` 唤起链接，可供快捷指令的“打开 URL”动作使用，再由用户按系统支持方式关联 AssistiveTouch。链接只打开已有会话并尝试保留唤起前画面，不自动发送问题、截图或启动共享。系统可能打开全屏 App；这不等于已经验证“点球恢复原处小窗”。

本轮未修改任何设备的 AssistiveTouch、快捷指令或多任务设置。需要在真机上分别观察两条入口的打开位置、输入键盘、收起恢复、原应用操作和目标画面；只有真实恢复小窗且正确取图通过后，才能把入口称为可用。PiP 不用于冒充可输入、可滚动和可点击来源的聊天窗。

## OpenAI 配置

默认型号为 `gpt-6-luna`，推理为低、搜索为自动、回答长度偏好为简短，输出与推理总预算为 32,768 tokens；默认应用指令与默认问题均可直接使用并编辑。目录也提供经官方能力核对的其他型号，账户是否有权限仍由服务端决定。高级型号 ID 可编辑；不认识或不兼容的型号会明确阻止发送，不静默换模型，不自动请求账户型号清单。

- 推理选项按目录能力标示，不支持的档位不会替用户静默转换。
- 搜索支持关闭、自动和必须联网。自动允许模型决定是否调用工具；必须联网发送实际工具约束，并根据工具结果检查是否完成。没有检索、检索失败、缺少来源，以及响应中断未拿到终态，会如实显示，不能把提示词或模型自述当作已经核验。
- 回答长度偏好与输出及推理总预算分开。高推理可能消耗较多预算并等待更久；预算也可能在可见回答生成前耗尽。
- 应用指令全文可编辑、复制、恢复默认，并可新建、应用、更新和删除本机预设，最多 20 套。设置中的全文就是实际发送的 OpenAI 应用指令，不暗中追加与其冲突的旧规则。
- 默认提问独立编辑，两家供应商共用。OpenAI 型号、推理、联网和应用指令设置仅用于 OpenAI；DeepSeek 继续使用既有 `deepseek-flash` 接口、关闭思考和原有请求行为。
- 请求开始时固定供应商、密钥、图片、问题、追问和配置值。参数编辑只影响后续请求；正在请求时不能切换供应商。旧请求的迟到片段不会写入新问题。

## 数据、缓存与生命周期

密钥分别保存在本机 `WhenUnlockedThisDeviceOnly` Keychain，不写入 UserDefaults、源码或日志，也不同步 iCloud。供应商与配置本地保存；画面与聊天仅存在当前进程内存中，无自建服务器、数据库、账户同步或无限后台队列。

请求直连供应商官方 HTTPS API，使用一次性 ephemeral URLSession，拒绝重定向，关闭 Cookie 和响应缓存。OpenAI 使用 Responses API、`store=false` 与实际 `web_search` 工具，DeepSeek 使用 Chat Completions。失败后由用户决定是否重试，不自动更换供应商。API 与工具用量以及数据保留由本人供应商账户政策决定。取消与本机清空不能撤回已发送数据，也不保证免计用量。

每张发送图片不超过 3 MiB；照片原文件不超过 20 MiB，缩小后最长边为 1280 像素。共享约每秒编码一帧、最长边 1600 像素、单帧 JPEG 不超过 2 MiB；实际源帧由系统交付。滚动缓存同时受 30 帧和 24 MiB 限制，不保存录屏文件；另保留一张固定证据、一张唤起前备选和有界在途帧，Data 共享不可变存储。预览按固定图 ID 降采样缓存，流式文字更新不会反复解码所有图片。历史最多 3 轮；非完整、被截断或中断的回答不进入追问上下文。显示超过 12,000 字时明确说明本地截断。

自动取图是一套**尚未真机验证的接收时间启发式**，不认证来源 App。实际后台返回时优先使用 `willEnterForeground`，窗口得 key、scene 激活和输入焦点作为一次性补充；不把控制中心或系统 sheet 引发的短暂 inactive 自动当成离开助手。前台分支仅接纳同一主窗口可观测的失 key→得 key 区间，过滤设置、照片、键盘、其他本 App 窗口和 scene deactivation。取图限于边界前 1.2 秒、最近 12 秒内；前台分支还要求帧在失焦后 1 秒之后。没有符合条件的帧就提示重取，不倒退复用旧候选，也不改用最新实时帧。窗口 key 状态仅属于本 scene，系统未必在外部 App 焦点切换时发出这些事件；即使有事件，全屏共享也可能包含仍可见的助手。**因此“能显示小窗”与“正确取得原应用画面”仍是必须一起通过的真机门槛。**

已发送请求提前申请系统有限后台执行时间，收起后尽力完成并保留回答；后台时间到期会取消并结束任务，不无限持续联网。`screen-capture` 后台模式只声明采集用途，不被当作无限网络权限。进程被系统终止后，内存内画面和会话不可恢复。

## 工程验证

单 Application target 使用 SwiftUI、UIKit、ScreenCaptureKit、ImageIO、PhotosUI、Security 等系统框架，无第三方依赖。最低系统为 27.0、仅 iPad，Debug/Release 共用 `Config/Base.xcconfig`，无额外开发团队、证书或付费服务配置。

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
  tests/OpenAIContractChecks.swift; do
  cat "$source"
  printf '\n'
done > .local/OpenAIContractChecks.swift
xcrun --sdk macosx swiftc -swift-version 5 -strict-concurrency=targeted -parse-as-library \
  .local/OpenAIContractChecks.swift -o .local/OpenAIContractChecks
.local/OpenAIContractChecks
```

CI 在 archive 前执行同一契约检查。当前 Windows 没有运行该命令，也未调用付费 API。静态检查只覆盖结构与代码状态边界。真机还应覆盖浏览、阅读、普通视频、无外接键盘、旋转、两条系统入口、画面被遮挡或取不到、共享授权拒绝、停止及中断、密钥锁定、配置切换、真实流式回答、工具约束、引用与取消。只有成功云构建才提供对应未签名产物；签名安装与运行另需验收。

## 测试构建

推送 `ipad-test-YYYYMMDD-NNN` 格式的 Git tag 会触发未签名构建，例如 `ipad-test-20261103-001`。日期后序号每天从 `001` 递增；每次修改使用新 tag，保留旧 tag，不移动或删除，也不创建 GitHub Release。构建固定检出该次事件的 commit，不跟随之后的分支更新。普通分支推送和 PR 不触发此工作流，仍可手动运行 `workflow_dispatch`。

工作流使用一个标准 `xcode-27` runner，无代码签名。成功后保留 unsigned IPA 与 SHA-256 校验文件的小型 artifact，IPA 上限 50 MiB、留存 1 天。构建步骤和资源限制以仓库当前工作流为准。本次实施不自动 push、tag 或触发 CI。

官方依据：[Apple iPad 多任务与 Slide Over](https://support.apple.com/en-ca/125309)、[Apple AssistiveTouch](https://support.apple.com/en-ie/111794)、[iOS ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios)、[UIWindow key 通知](https://developer.apple.com/documentation/uikit/uiwindow/didbecomekeynotification)、[有限后台执行](https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:))、[OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision)、[OpenAI 网页搜索](https://developers.openai.com/api/docs/guides/tools-web-search)、[OpenAI 流式响应](https://developers.openai.com/api/docs/guides/streaming-responses)、[DeepSeek Vision](https://api-docs.deepseek.com/guides/vision/)。
