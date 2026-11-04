# 图像、音频与多轮问答的上下文

本文解释媒体输入和会话上下文的区别，并说明当前 Android 实现的边界。界面上的一个聊天窗口、本地历史记录和 API 的会话状态是不同层面的概念，不能互相替代。

## 音频：转写原文，再翻译或解释

“语音转文字”是转写（transcription）；“把英文改成中文”是翻译（translation）。两者应分别保留结果，避免将模型改写的文字当作录音逐字原文。

对于录完一段再查看的场景，可以使用以下流程：

```text
固定录音片段
    ↓
转写模型 → 原语言的文字
    ↓
文字模型 + Instructions + 本轮问题
    ↓
翻译、解释或总结
```

这条流程可以分别显示原文与解释。转写成功但解释失败时，可以复用转写重试；继续讨论同一段内容时，也可以把已有文字作为上下文，无需每轮重新上传音频。仅需要原文的产品流程可在转写后结束，不必再调用文字模型。

截至 2026-11-05，OpenAI 官方文件转写指南使用 `gpt-transcribe` 和 `/v1/audio/transcriptions`。具体服务是否支持该模型、参数和响应格式，仍需按实际配置验证。文件转写与持续实时音频是不同接口流程。

参考：[OpenAI 文件转写文档](https://developers.openai.com/api/docs/guides/speech-to-text)。

## 图片：文字历史不等于原图仍在上下文里

支持视觉输入的模型可以根据图片中的文字、布局和细节回答问题。需要多轮讨论同一张图时，后续请求必须仍能引用或包含这张图。

常见的上下文管理方式如下：

| 方式 | 后续请求如何继续 | 需要理解的边界 |
| --- | --- | --- |
| 客户端管理 | 携带相关文字历史，并在需要时再次附原图 | 应用可以自动完成，用户不必手动重新选图；仍有图片输入成本 |
| 服务端会话或响应链 | 使用会话标识或前一响应标识延续服务端上下文 | 依赖服务支持及保存策略；不能假定所有兼容接口都提供同等能力 |
| 只保留文字摘要 | 后续只发送摘要和文字消息 | 能延续部分语义，不能保证仍能查看图片中未被描述的细节 |

因此，“沿用原图”应当对应真实可用的图片证据。“仅文字”可以继续携带先前讨论，但不能承诺模型还能查看原图。图片已经释放或历史未保存图片时，需要明确提示；更新画面则是取得一张新证据。

图片输入会计入 token。少上传一次图片字节、引用已有文件或延续服务端会话，不等于历史上下文不再产生输入费用。

参考：[OpenAI 会话状态](https://developers.openai.com/api/docs/guides/conversation-state)、[OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision)。

## 当前会话可用与长期保存是两个开关

- 当前会话可以在内存中保留图片，用于追问；这不要求把图片写入长期历史。
- 历史保存了问题和回答，不代表同时保存了原图。
- 音频转写可以作为文字保存，不需要为了文字追问一直保留原始音频。
- 图片如果需要跨进程、跨重启恢复原图问答，就需要明确的图片保存、关联与清理策略，或者相应服务端上下文支持。
- 窗口收起、请求取消、会话结束、媒体缓存释放和历史删除，应分别定义生命周期，不能用其中一个动作隐含替代其余动作。

## 当前 Android 实现

以下描述当前 Android 实现：

- [AiProvider.kt](../apps/android/app/src/main/java/com/alwayside/android/AiProvider.kt) 使用 Responses 流式请求、`store: false` 和客户端提供的文字历史；当前没有使用服务端会话或响应链。原图通过当前请求的 `input_image` 附入。
- [ChatSession.kt](../apps/android/app/src/main/java/com/alwayside/android/ChatSession.kt) 实现先转写、再解释，或仅输出转写。历史恢复保留原始转写和所选文字，后续文字追问仍将其作为引用资料。请求固定配置、问题、原文和证据；取消及新会话用隔离标识阻止迟到结果写回。
- [AssistantService.kt](../apps/android/app/src/main/java/com/alwayside/android/AssistantService.kt) 分别管理截图、音频与文字小窗；活动会话保留图片，并提供沿用、更新、仅文字的选择。取消录音先使完成回调失效再丢弃片段，不进入转写。待处理会话可移交给应用内聊天。
- [HistoryStore.kt](../apps/android/app/src/main/java/com/alwayside/android/HistoryStore.kt) 保存有界消息、来源、状态、转写和所选原文，不在 JSON 内嵌媒体。查询开始、取消、失败和完成均归档到原会话。
- [ConversationMediaStore.kt](../apps/android/app/src/main/java/com/alwayside/android/ConversationMediaStore.kt) 默认使用私有 noBackup 目录保存首图与当前追问图，两者相同时共用一份文件。图片最长边 2400 像素、JPEG 质量 90，单图不超过 4 MiB，总容量上限 128 MiB；达到容量时提示保存失败，不静默删除其他会话的图片。删除或淘汰会话时同时删除它的两张图片；原始音频仍不进入长期历史。

当前 APK 的功能与使用方式以仓库 README 为准；独立交互原型不能视为已实现功能。
