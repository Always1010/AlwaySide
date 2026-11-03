# AlwaySide iPad

个人自用的原生 SwiftUI 屏幕问答助手。目标为 iPadOS 27.0 及以上，工程与共享 scheme 位于 `AlwaySide.xcodeproj`。源码包含系统共享选择器、画面预览与冻结、文字问题、本机钥匙串和供应商直连接口。

**工程的未签名 IPA 需要个人签名后才能安装。** Windows 上的结构检查不等于构建成功，只有成功完成的云构建才生成对应产物；安装和真机运行还需独立验证。此项目不依赖 Swift Playground。

## 使用流程

1. 在设备上运行应用后，先在主界面选择供应商，再打开钥匙按钮保存自己的 API Key。保存和删除密钥只访问本机 Keychain，不进行网络验证。
2. 点击“开始共享”，在系统界面选择允许共享的内容。共享只在本地更新画面，不会持续发送给模型。受保护的媒体或不在共享范围内的内容可能不可见。
3. 切换到需要阅读的应用，再返回 AlwaySide，点击“取帧并固定预览”。首次取帧优先使用返回时保留的一张候选画面；再次取帧使用最新画面。共享时间标记为应用接收画面的时间。切换前后的帧仍可能包含 AlwaySide 自身，请实际检查预览、时间和来源。
4. 选择供应商、输入问题，勾选确认发送，再点击“提问”。每次发送一张固定图片、本次问题及同图最近最多 3 轮文字上下文。每次追问都需要重新确认。新取帧或切换供应商会清空旧上下文。
5. 点击取消结束当前请求；点击停止同时清除画面、问题和追问。应用进入后台时也会取消当前模型请求，并撤销待发送的确认。取消无法撤回已经发送的数据，也不保证供应商不计用量。

照片按钮通过系统 PhotosPicker 单选一张图片，是独立的手动备选输入；它不能证明或替代跨应用共享。导入后显示的时间为导入时间。应用只保留缩小后的 JPEG，不读取整个相册。

首版提供画面和文字问答，不请求麦克风权限、不录音、不转写、不播放语音，也没有视频片段分析。回答一次性显示；没有流式文字、自动重试、自动换供应商、后台上传队列或账户间同步。

## 数据与请求

- 供应商固定为 OpenAI `gpt-6-luna`（`https://api.openai.com/v1/responses`）与 DeepSeek `deepseek-flash`（`https://api.deepseek.com/chat/completions`）。模型名称及图片格式依据下方官方文档于 2026-11-03 核对；设备网络和账户模型权限仍需另行验证。
- 用户只需保存将要使用的供应商密钥。每家密钥独立存储于 `WhenUnlockedThisDeviceOnly` Keychain，不使用 iCloud Keychain、不写入 UserDefaults、源码或日志。供应商选择本地保存，不保存聊天与图片文件。
- 网络使用一次性的 ephemeral URLSession，只发送至所选官方 HTTPS API，拒绝重定向，关闭 Cookie 和响应缓存。失败后由用户决定是否重新发送，不调用其他模型或工具。
- OpenAI 请求使用 `store=false`、`reasoning.effort=none`；DeepSeek 使用关闭思考的单次图文请求。两者回答上限均为 1400 token。客户端设置请求与资源超时，但实际费用和数据保留由供应商账户政策决定。
- 每张发送图片不超过 3 MiB；照片导入原文件最多 20 MiB，缩小到最长边 1280 像素。历史最多 3 轮，每轮携带最多 2000 字问题与 4000 字回答。非完整回答不进入追问上下文。
- 画面、照片与聊天保留在当前进程内存中。停止共享会清除当前证据及追问；不会删除本人供应商账户已接收的数据。没有自建服务器、数据库或统一开发者密钥。

## 工程验证与运行边界

单 Application target 使用系统 SwiftUI、ScreenCaptureKit、ImageIO、PhotosUI、Security 等框架，无第三方依赖。配置在 `Config/Base.xcconfig`，最低系统为 27.0，设备家族仅 iPad，Debug/Release 均共享该配置。没有开发团队、证书、额外 entitlement 或付费服务配置。`UIBackgroundModes` 仅声明 `screen-capture`，用于共享期间采集；不会以此在后台持续请求模型。

在具有 Xcode 27.1 的 Apple 构建环境中，可进行不签名的编译检查：

```sh
xcodebuild -project apps/ipad/AlwaySide.xcodeproj \
  -scheme AlwaySide -configuration Debug \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

此命令未在当前 Windows 设备执行；它既不生成有效个人签名，也不构成可安装交付。真机验收还必须覆盖系统授权和拒绝、共享 App 与范围、受保护内容、前后台切换、重复取帧、停止和中断、照片取消、Keychain 锁定及两家真实接口的响应与取消。没有调用付费 API 进行本次验证。

## 测试构建

推送 `ipad-test-YYYYMMDD-NNN` 格式的 Git tag 会触发未签名构建，例如 `ipad-test-20261103-001`。日期后序号每天从 `001` 递增；每次修改使用新 tag，保留旧 tag，不移动或删除，也不创建 GitHub Release。构建固定检出该次事件的 commit，不跟随之后的分支更新。普通分支推送和 PR 不触发此工作流，仍可手动运行 `workflow_dispatch`。

工作流只使用一个标准 `xcode-27` runner，超时 15 分钟，无依赖缓存、无代码签名。成功后保留一个包含 unsigned IPA 与 SHA-256 校验文件的小型 artifact，IPA 上限 50 MiB，留存 1 天；不会发布可直接安装的签名版本或启用付费用量。

官方依据：[OpenAI GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna)、[OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision)、[DeepSeek Vision](https://api-docs.deepseek.com/guides/vision/)、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[Apple ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit)。
