# AlwaySide

[![下载 APK](https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD-APK-3DDC84?style=plastic&logo=android&logoColor=white)](https://github.com/Always1010/AlwaySide/releases)
[![Release](https://img.shields.io/github/v/release/Always1010/AlwaySide?style=plastic&logo=github)](https://github.com/Always1010/AlwaySide/releases/latest)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?style=plastic)](apps/android/app/build.gradle.kts)
[![发布构建](https://img.shields.io/github/actions/workflow/status/Always1010/AlwaySide/release.yml?style=plastic&label=%E5%8F%91%E5%B8%83%E6%9E%84%E5%BB%BA&logo=github)](https://github.com/Always1010/AlwaySide/actions/workflows/release.yml)

Android 悬浮 AI 助手，支持截图问答、应用内音频转写与翻译、选中文字提问。通过悬浮球在其他应用上方查看回答、继续对话，也可直接在应用内聊天。

[使用说明](docs/usage.md) · [构建与开发](apps/android/README.md)

Android 10 及以上版本，需要自行配置 API 服务和密钥，调用费用由所用服务决定。

## 功能

| 功能 | 支持范围 |
| --- | --- |
| 截图问答 | 截取整屏或框选区域，翻译、解释、总结画面内容；支持预设提示词自动提问 |
| 图片追问 | 沿用原图、更新当前画面，或仅发送文字；框选后更新画面可沿用选区 |
| 内部录音 | 录制应用内播放的声音，输出原文、原文与中文翻译，或按预设问题生成回答；支持基于转写原文追问 |
| 文字提问 | 从系统文字选择菜单或纯文本分享入口发送内容；无悬浮权限时进入应用内聊天 |
| 会话管理 | 悬浮小窗与应用内聊天可衔接，支持从历史继续对话、查看保存的截图及批量删除记录 |
| 窗口与外观 | 小窗可移动、缩放、固定；可调整悬浮球外观、应用主题和明暗模式 |
| 自定义配置 | 快捷手势、截图与音频提示词、模型与生成参数；联网搜索需服务与模型支持 |

## API 与权限

在设置的「API 连接」中填写服务地址和密钥，在「模型与生成参数」中选择模型。服务地址使用 HTTPS，以 `/v1` 结尾；可读取模型列表或手动填写模型 ID。

- **问答**：服务须支持 Responses 流式请求；截图问答还需模型支持图片输入。仅支持 Chat Completions 的服务不适用。
- **音频**：需额外配置转写模型，服务须提供文件转写接口。模型出现在列表中不代表支持所有功能。
- **权限**：使用悬浮助手需允许悬浮显示；截图、录音按需申请系统采集权限。应用内文字聊天与历史查看不需要悬浮权限。

保存配置不会自动测试连接。首次使用可通过首页「开始体验」练习截图与追问；该演练使用内置示例，不调用 API、不消耗 Token，也不验证真实服务。

<details>
<summary>默认手势</summary>

| 操作 | 空闲时 | 录音时 |
| --- | --- | --- |
| 单击 | 展开菜单 | 展开菜单 |
| 双击 | 开始录音 | 结束并处理 |
| 三击 | 不操作 | 取消并丢弃录音 |
| 长按 | 截图问答 | 提示先结束或取消录音 |

手势可在设置中修改；菜单也提供截图、框选和录音入口。首页开关可打开或关闭悬浮助手。

</details>

## 数据与限制

- API 密钥加密保存在本机。提问所需的文字、截图或录音会发送到所配置的 API 服务，请确认服务可信；屏幕共享期间不会持续上传画面。
- 会话保存在本机，默认保存截图，可在设置中关闭。删除会话会一并删除关联图片；转写文字随会话保留，原始录音不进入长期历史。
- 截图、内部录音和悬浮显示受系统与目标应用限制。应用不会绕过限制，也不会在内录失败时改用麦克风。

## 文档

- [使用说明](docs/usage.md)：API 配置、采集权限、截图与录音操作、常见问题。
- [图片、音频与会话](docs/ai-media-context.md)：媒体发送、上下文与本地保存规则。
- [Android 工程说明](apps/android/README.md)：构建环境、本地验证与发布流程。
