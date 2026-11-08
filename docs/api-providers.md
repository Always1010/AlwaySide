# AI 供应商、协议与数据格式

资料核对日期：**2026-11-06**。本文描述 Android 客户端当前实现以及接入边界。官方文档核对和本地离线测试不等于真实账号联调；本文没有声称已用付费 API 验证模型可用性、权限、地域或计费。

示例中的 `YOUR_MODEL_ID`、`YOUR_TRANSCRIPTION_MODEL_ID`、`YOUR_API_KEY`、`YOUR_WORKSPACE_ID` 和 `BASE64_IMAGE_BYTES` 均为占位符，必须替换。内置推荐和能力限制中出现的型号不保证当前账号一定能访问。请求示例中的图像字节省略，不能直接发送。

## 1. 服务、密钥与用途配置分别保存

`ProviderKind` 表示供应商，`ApiProtocol` 表示回答的线上数据格式。设置中只有「AI 服务」入口，其下只有「配置问答服务」「配置转写服务」「管理 API 服务」三行。管理页按实际服务商分组列出 API 密钥，一条 Key 包含自己的 API 地址及接口信息；添加时先选服务商，再填 Key 与名称。Key 统一在管理页编辑，不提供独立接入点或密钥授权页面。

图文问答与音频转写分别显示服务商卡片，每个服务商在每个用途中只保留一张。添加卡片依次选择服务商、该服务商已有 Key、模型及默认参数；不另填模型配置名称。首项保存后自动选中，后续新增不切换；点击服务行选用，行尾图标编辑或删除。两个用途可共用同一服务及 Key，也可分开。内部 `ServiceCatalog` 仍以服务商、接入点、凭证、用途配置和绑定等实体表达关系，这些内部实体不对应额外的用户配置层级。

保存不等于验证可用，模型目录可读取也不代表支持图片、搜索或转写。回答协议和文件转写支持分别判断，不能从 Chat 或 Responses 兼容性推断转写能力。

| 供应商 | 新建 Key 默认回答协议 | 基础地址默认值 | 回答接口 |
| --- | --- | --- | --- |
| OpenAI | Responses | `https://api.openai.com/v1` | `POST /responses` |
| DeepSeek | Responses | `https://api.deepseek.com` | `POST /responses` |
| Qwen | Chat Completions | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `POST /chat/completions` |
| Gemini | Gemini 原生 GenerateContent | `https://generativelanguage.googleapis.com/v1beta` | `POST /models/{model}:streamGenerateContent?alt=sse` |
| Claude | Claude Messages | `https://api.anthropic.com/v1` | `POST /messages` |
| 自定义兼容服务 | Responses | `https://api.openai.com/v1`，使用前修改 | 按明确选择的协议拼接 |

服务商名称统一使用 Qwen。请使用所选 Qwen 接入点对应地域的 API 地址和 API Key。`ProviderKind.QWEN` 的专有参数适配仅针对 Qwen 模型，不代表接入点上其他模型的专有能力均受支持。

基础地址包含版本或兼容路径，**不包含回答接口路径**。例如 Claude 填到 `/v1`，Qwen 填到 `/compatible-mode/v1`；DeepSeek 默认直接使用域名。自建代理可带路径前缀。只能使用 HTTPS，禁止用户名、密码、查询参数、片段、路径回退、编码路径混淆和无效端口。客户端不跟随重定向，也不把密钥放在查询字符串。

当前 Chat Completions 适配器允许 OpenAI、Qwen、Gemini 和自定义兼容服务；DeepSeek、Claude 接入点使用表中默认协议。Gemini 默认仍为 GenerateContent，可在高级配置中明确选择 Chat 兼容模式并填写兼容基础地址。供应商具有其他兼容接口，并不代表本客户端已经实现其全部扩展。自定义服务的参数、视觉和搜索能力由服务实际实现决定，获取模型列表成功也不能证明这些能力。

### 请求头、版本与工作区

| 协议 | 鉴权及专用请求头 |
| --- | --- |
| Responses / Chat Completions | `Authorization: Bearer YOUR_API_KEY` |
| Gemini 原生 | `x-goog-api-key: YOUR_API_KEY` |
| Claude Messages | `x-api-key: YOUR_API_KEY`；`anthropic-version: 2026-10-21`；填写工作区时加 `anthropic-workspace-id: YOUR_WORKSPACE_ID` |

回答使用 `Content-Type: application/json`、`Accept: text/event-stream`。Claude API 版本默认 `2026-10-21`；版本字段按 `YYYY-MM-DD` 校验。工作区字段只用于 Claude 请求头，不能用它替代 Qwen 域名中的工作区。

Qwen 的 Key、模型列表和接入点具有地域边界。新接入建议按控制台选择工作区专属域名，例如北京 `https://YOUR_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`、新加坡 `https://YOUR_WORKSPACE_ID.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1`；其他地域按官方表填写。仓库保留既有默认域名，用户可直接编辑基础地址。跨地域复制 Key 或模型 ID 不能保证可用；更改域名后应重新填写该接入点的密钥。[Qwen 地域与接入点](https://www.alibabacloud.com/help/en/model-studio/regions)、[Qwen Chat 接口与工作区域名](https://www.alibabacloud.com/help/en/model-studio/qwen-api-via-openai-chat-completions)

## 2. 四种回答请求格式

普通会话历史只带文字：保留最近最多 24 条非空 `user` / `assistant` 消息，文字预算 65,536 字符。当前问题可以带一张图；以前的截图不会随着普通文字历史重复上传。Instructions 映射到各协议的系统指令位置。Claude 搜索暂停后的显式继续请求例外，需要保留原生内容块，见后文。

### Responses：OpenAI 与 DeepSeek

`POST {baseUrl}/responses`。只有文字时，下面 `content` 数组只保留 `input_text`。旧历史用 `{"role":"assistant","content":"上一轮回答"}` 等简写项。

```json
{
  "model": "YOUR_MODEL_ID",
  "instructions": "请用中文解释。",
  "input": [{"role": "user", "content": [
    {"type": "input_text", "text": "这张图写了什么？"},
    {"type": "input_image", "image_url": "data:image/png;base64,BASE64_IMAGE_BYTES"}
  ]}],
  "store": false,
  "stream": true,
  "max_output_tokens": 4096
}
```

显式设置时再加 `reasoning: {"effort":"low"}`、`text: {"verbosity":"low"}`、`temperature` 或 `top_p`；搜索为 `tools: [{"type":"web_search"}]`，仍需模型支持。DeepSeek 使用相同请求框架，但兼容能力与 OpenAI 不完全相同。[OpenAI 文字生成](https://developers.openai.com/api/docs/guides/text?api-mode=responses)、[DeepSeek Responses 指南](https://api-docs.deepseek.com/guides/responses_api/)

### Chat Completions：Qwen、Gemini 高级兼容模式与兼容服务

`POST {baseUrl}/chat/completions`。纯文字当前消息的 `content` 直接是字符串；带图时使用内容数组。

```json
{
  "model": "YOUR_MODEL_ID",
  "messages": [
    {"role": "system", "content": "请用中文解释。"},
    {"role": "user", "content": [
      {"type": "text", "text": "这张图写了什么？"},
      {"type": "image_url", "image_url": {"url": "data:image/png;base64,BASE64_IMAGE_BYTES"}}
    ]}
  ],
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

OpenAI／自定义连接显式参数使用 `max_completion_tokens`、`reasoning_effort`、`verbosity`，搜索为 `web_search_options: {}`。这些名称不能代表任意兼容服务都支持。Qwen有独立字段映射和本地限制，见参数章节。[OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Qwen Chat API](https://www.alibabacloud.com/help/en/model-studio/qwen-api-via-openai-chat-completions)

Gemini 的 Chat 高级兼容模式使用 `https://generativelanguage.googleapis.com/v1beta/openai` 基础地址和 Bearer 鉴权；输出上限为 `max_tokens`，reasoning 为兼容接口的 `reasoning_effort`。此模式明确拒绝搜索和 verbosity，要求搜索时选择原生协议。兼容接口的 effort 映射由 Google 定义，与下文应用自定的原生 2.5 budget 表分别维护。[Gemini OpenAI 兼容接口](https://ai.google.dev/gemini-api/docs/openai)

### Gemini：原生 GenerateContent

`POST {baseUrl}/models/YOUR_MODEL_ID:streamGenerateContent?alt=sse`。模型 ID 放在路径，正文没有 `model` 或 `stream`。接受 `models/模型ID` 或裸 ID，最多移除一次前缀；模型 ID 禁止斜线、冒号、百分号和查询字符等路径注入。

```json
{
  "systemInstruction": {"parts": [{"text": "请用中文解释。"}]},
  "contents": [{"role": "user", "parts": [
    {"text": "这张图写了什么？"},
    {"inlineData": {"mimeType": "image/png", "data": "BASE64_IMAGE_BYTES"}}
  ]}],
  "generationConfig": {"maxOutputTokens": 4096}
}
```

纯文字移除 `inlineData`；助手历史角色映射为 `model`。支持的模型开启搜索时加 `tools: [{"google_search":{}}]`。思考配置位于 `generationConfig.thinkingConfig`。

本项目使用 **GenerateContent** 的 `contents`、`parts`、`inlineData`、`generationConfig` 和 `groundingMetadata`。Google 的部分通用指南已转向 Interactions API；不要把其 `steps`、`model_output`、`thought` 步骤或 `generation_config` 请求格式混进此适配器。[GenerateContent API 定义](https://ai.google.dev/api/generate-content)

### Claude：Messages

`POST {baseUrl}/messages`。系统指令为顶层 `system`，不插入 `system` 角色消息。

```json
{
  "model": "YOUR_MODEL_ID",
  "system": "请用中文解释。",
  "messages": [{"role": "user", "content": [
    {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "BASE64_IMAGE_BYTES"}},
    {"type": "text", "text": "这张图写了什么？"}
  ]}],
  "max_tokens": 4096,
  "stream": true
}
```

纯文字去掉 `image` 块。`max_tokens` 必填，因此用户留空时客户端发送 8192。搜索采用 `tools: [{"type":"web_search_20250305","name":"web_search"}]`；客户端选择这一基础工具版本，不自动启用动态过滤或代码执行。支持模型的 effort 置于 `output_config.effort`，适配器按模型添加思考设置。[Claude Messages API](https://platform.claude.com/docs/en/api/http/messages/create)

## 3. SSE 增量与完成判定

下面只保留影响解码的字段。每个 SSE 事件由空行终止；传输解析器接受 BOM、CRLF、注释及多行 `data:`，未以空行完成的尾部事件会丢弃。所有协议要求非空文字和正常完成状态；收到一部分文字不代表完整成功。

### Responses

```text
event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"你好"}

event: response.completed
data: {"type":"response.completed","response":{"status":"completed","error":null,"incomplete_details":null,"output":[{"type":"message","content":[{"type":"output_text","text":"你好","annotations":[]}]}]}}

```

最终正文与已收增量不一致会失败；没有增量时可从最终正文取文字。`response.incomplete`、`response.failed`、`error` 或正常完成事件前 EOF 均失败。`[DONE]` 本身不构成 Responses 成功证据；DeepSeek 官方流也不使用该标记。推理增量不会混入答案。[OpenAI Responses 事件](https://developers.openai.com/api/reference/resources/responses/streaming-events)

### Chat Completions

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"你好"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

```

单候选 `index: 0`、`delta.content` 和 `finish_reason: stop` 构成当前成功条件。仅 `[DONE]`、仅 EOF、`length`、`content_filter`、客户端工具调用和 refusal 均不能成功。`reasoning_content` 不显示。正常结束后的 usage-only 空 `choices` 是协议允许的元数据；当前读取循环可在已满足完成条件后结束，因此不依赖额外 usage 数据。

### Gemini GenerateContent

```text
data: {"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"你好"}]}}]}

data: {"candidates":[{"index":0,"finishReason":"STOP"}]}

data: {"candidates":[{"index":0,"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/source","title":"来源示例"}}]}}],"usageMetadata":{"totalTokenCount":100}}

```

只输出普通 `parts[].text`，忽略 `thought: true`。必须看到候选的 `finishReason: STOP`，并继续读到 EOF，保留可能后到的 grounding／usage 元数据。`MAX_TOKENS`、SAFETY 等其他结束原因、`promptFeedback.blockReason`、错误对象、无候选、无答案和缺少 STOP 的 EOF 都失败。此流没有应用要求的 `[DONE]` 终止标记。

### Claude Messages

```text
event: message_start
data: {"type":"message_start","message":{"content":[]}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

event: message_stop
data: {"type":"message_stop"}

```

必须验证消息开始、块开始／增量／结束和终止原因。`end_turn`、`stop_sequence` 是成功结束；`max_tokens`、上下文长度超限、refusal、搜索工具错误、未支持的客户端 `tool_use` 均失败。thinking／signature 增量不显示，但原生块会保留用于搜索继续。

`pause_turn` 表示服务端搜索尚未完成，不保存为完整回答。客户端返回专门的暂停状态；用户显式继续时，重发原请求消息加完整 assistant 原生块，保留思考签名、工具输入／结果和引用。它不是网络自动重试，不能仅把已显示的文字拼回请求。[Claude 流式事件](https://platform.claude.com/docs/en/build-with-claude/streaming)、[pause_turn 处理](https://platform.claude.com/docs/en/claude_api_primer)

### 来源显示

Responses 使用 URL citation annotations；Chat Completions 使用兼容的 `delta.annotations[].url_citation`；Gemini 使用候选 `groundingMetadata.groundingChunks[].web`，并兼容 `citationMetadata.citationSources`；Claude 使用 `web_search_result_location` 引用。

客户端将可用来源追加成 Markdown 来源列表，最多 10 条、按 URL 去重，只允许无内嵌账号的 HTTP(S) URL，并清理标题中的 Markdown 控制字符。当前呈现不做按文字区间插入引用。Qwen Chat 明确不提供结构化搜索来源，客户端不会假造来源列表或把普通模型正文中的链接当作结构化证明；联网开关只表示允许搜索，不证明每轮都触发搜索。[Qwen搜索协议差异](https://help.aliyun.com/en/model-studio/web-search)

`AnswerDecoder.searchUsed`／`AiProvider.lastSearchUsed` 是来源元数据驱动的诊断。以 Gemini 为例，只在存在非空 `groundingMetadata.webSearchQueries` 或有效 web grounding 来源时标记；正文声称“已搜索”不构成证据。Qwen Chat 无法从当前返回格式确认实际执行搜索，false 不能解释成已证明“没有搜索”。诊断值在成功完成请求后读取，也不能代替供应商计费／使用量记录。

### Gemini 搜索建议片段

原生 GenerateContent 的 `groundingMetadata.searchEntryPoint.renderedContent` 是 Google 提供的搜索建议 HTML／CSS，独立于回答正文和 Markdown 来源。解码器通过 `searchSuggestions` 元数据保留片段，允许 STOP 后到达，不读取 Interactions 的 `steps`／`search_suggestions`。片段最大 32,768 字符；超长内容拒绝，不截断供应商 HTML。

`Context.searchSuggestionsView` 将原片段放入固定 128dp 高、内部可滚动的原生 WebView；禁用 JavaScript、DOM／数据库存储、文件与 content 访问和网络资源加载，不添加 JavaScript bridge，也不求值字符串。只允许用户手势触发的主框架 HTTP(S) 链接通过外部 ACTION_VIEW 打开，要求有效主机、无内嵌账号；自动导航、重定向及自定义协议不会在 WebView 中加载。外部图片、字体或样式资源不会请求，供应商内联 HTML／CSS 原样显示。

片段作为有长度上限的消息／历史元数据保存，展示时单独渲染，不混入之后发送的文字历史。旧历史没有此字段时按空字符串处理；读取历史和渲染时同样保留长度上限，不能因为来自本地历史而放宽 WebView 权限。

## 4. 参数映射与模型限制

新建图文问答卡片默认可选生成参数为空；空值表示不发送，供应商自行选择默认。参数值按协议和模型适配，未知时留空。图文问答配置先校验：输出上限为空／0 或 16–131072；temperature 为有限数 0–2；top_p 为有限数 0–1；两种采样参数只能设置一种。供应商及模型还可能有更窄范围。

| 应用字段 | Responses | 普通 Chat | Qwen Chat | Gemini | Claude |
| --- | --- | --- | --- | --- | --- |
| maxOutputTokens | `max_output_tokens` | `max_completion_tokens` | 依模型选 `max_tokens` / `max_completion_tokens` | `generationConfig.maxOutputTokens` | `max_tokens`，空时 8192 |
| reasoning | `reasoning.effort` | `reasoning_effort` | 支持模型的 `reasoning_effort`，部分模型仅支持 none 映射 | `thinkingBudget` / `thinkingLevel` | `output_config.effort` 与 `thinking` |
| verbosity | `text.verbosity` | `verbosity` | 拒绝 | 拒绝 | 拒绝 |
| temperature / topP | `temperature` / `top_p` | 同左 | 更窄范围 | `generationConfig.temperature` / `topP` | 默认 API 版本拒绝 |
| webSearch | `tools[].type=web_search` | `web_search_options` | `enable_search` | `tools[].google_search` | `web_search_20250305` |

### DeepSeek

当前本地能力限制：`deepseek-v4-pro` 可以使用 Responses 搜索，但不能接收图片；`deepseek-flash` 可接收图片，禁止开启当前 Responses 搜索。其他模型 ID 可手工填写，但不会因为出现在列表中而标记为已验证。verbosity 被本地拒绝，避免发送服务端接受却不生效的选项。

官方说明还包括采样在不同模型／思考模式下生效范围不同：thinking 模式 temperature 无效；Flash 的 top_p 在 thinking 模式下限为 0.95、非 thinking 模式固定 1；V4 Pro 的 top_p 在 thinking 模式被忽略。当前代码只对这些采样值执行通用范围验证，开发者不应将“已发送”写成“必然生效”。服务端可能静默忽略兼容参数，因此新增参数前必须核对其兼容表。[DeepSeek 参数兼容说明](https://api-docs.deepseek.com/guides/responses_api/)

### Qwen

当前适配器对 Qwen3.5–3.8 Plus／Flash 和 Qwen3.7–3.8 Max 使用 `max_completion_tokens`；其余使用 `max_tokens`。temperature 必须小于 2，top_p 必须大于 0。verbosity 不支持。

Qwen3.8 的 Max、Flash、2.4t-a95b、27b、Omni-Flash 家族使用 `reasoning_effort`；其他已识别的混合思考家族仅在显式 none 时发送 `enable_thinking: false`。没有文档支持的其他推理程度直接报错，不把 low／high 擅自转换成开关。thinking／instruct／coder／character 等特殊模型不套用混合开关。

搜索限定在适配器列出的 `qwen-max/plus/flash`、Qwen3.8、Qwen3.7、Qwen3.6、Qwen3.5 具体家族；未识别模型开启搜索会失败。Omni 家族中适配器识别的 Qwen3.8 Omni-Flash、Qwen3.5 Omni-Plus／Flash 会附带 `search_options: {"search_strategy":"agent"}`。当前不暴露所有Qwen搜索策略，也不请求 Qwen 原生 `search_info`。具体模型名单以 `ChatCompletionsAdapter.qwenOptions` 为准。

### Gemini

Gemini 2.5 使用 token budget；3.x／4 Argon 的已知模型使用 thinking level，两者不能混发。下面 budget 是**本应用对 UI 程度的映射**，不是 Google 对这些程度的官方等价定义。

| reasoning | 2.5 Flash／Flash-Lite thinkingBudget | 2.5 Pro thinkingBudget |
| --- | --- | --- |
| 空 | 不发送 | 不发送 |
| none | 0 | 拒绝：不能关闭思考 |
| minimal | 512 | 512 |
| low | 1024 | 1024 |
| medium | 8192 | 8192 |
| high | 24576 | 32768 |
| xhigh / max | 拒绝 | 拒绝 |

已知 level 家族：3 Pro 仅 low／high；3.1 Pro、3.7／3.8 Flash、4 Argon 支持 low／medium／high；3 Flash、3.1 Flash-Lite、3.5／3.6 Flash 支持 minimal／low／medium／high；3.1 Flash-Lite Image 仅 minimal／high。显式 none 不等于 minimal，不能关闭思考的模型会拒绝；未知模型必须清空 reasoning。includeThoughts 固定 false。

Google Search 也按适配器已知家族校验，未知模型不自动猜测支持；2.0 Flash-Lite、2.5 Flash Image、audio 变体等被排除。模型默认值和可用性会变，维护时应同时更新映射、测试与本文。[GenerateContent 思考指南](https://ai.google.dev/gemini-api/docs/generate-content/thinking?hl=en)、[Google Search 支持模型](https://ai.google.dev/gemini-api/docs/google-search)

### Claude

默认 `anthropic-version: 2026-10-21` 已移除 temperature、top_p、top_k，连默认数值也不能发送。本应用不提供 top_k，并拒绝该版本的其他两个采样字段。确需旧版本采样时，用户必须明确改为 `2023-06-01`：temperature 范围 0–1，模型仍有进一步限制，不会自动降级 API 版本。[Claude 2026-10-21 迁移说明](https://platform.claude.com/docs/en/api/migration-2026-10-21)

适配器已知 adaptive 家族包括 Opus 4.6／4.7／4.8、Sonnet 4.6、Opus 5、Sonnet 5、Haiku 5.5、Fable 5、Mythos 5／Preview。设置已支持 effort 时发送 adaptive thinking 和 `output_config.effort`；Opus 4.5 支持 effort，但不自动开启 adaptive。minimal 被拒绝；xhigh 只在代码列出的新家族上允许；Opus 4.5 不允许 max。

显式 none 通常发送 disabled；Sonnet 5.5 使用 between_tools；Opus 5.5、Fable 5、Mythos 5／Preview 禁止关闭思考。未知模型最好留空 reasoning，由服务选择默认。Opus／Sonnet／Haiku 4.5 的客户端输出上限为 64000。旧 API 的采样约束还依思考模式及新旧模型而变，具体条件见 `ClaudeMessagesAdapter`，不能为绕过失败而自动清空参数。

## 5. 内置推荐与获取模型列表

### 内置推荐配置

[`apps/android/app/src/main/assets/model-presets.json`](../apps/android/app/src/main/assets/model-presets.json) 是随应用打包的离线推荐表，由 `ModelPresets` 读取。按 `ProviderKind` 和 `ModelPurpose` 分组，每组维护一个 `defaultModelId`、精简 `models` 列表及适用的回答协议或文件转写接口。列表顺序就是显示顺序；每个型号只维护实际 ID、显示名称和简短标签。`checkedAt`、`sourceUrls` 和必要的 `regionNote` 供维护时核对；这些来源链接不会在配置时自动请求。

图文问答只收录支持文字、图片输入和文字回答的型号；**音频转写仅 OpenAI 收录 `gpt-transcribe`，其他服务商的转写默认值和列表全部留空。** 自定义兼容服务的两种用途均无内置推荐。模型支持音频、实时语音或语音合成，不代表兼容当前的文件转写接口。初始型号依据 [OpenAI 模型目录](https://developers.openai.com/api/docs/models)、[OpenAI 文件转写](https://developers.openai.com/api/docs/guides/speech-to-text)、[DeepSeek 模型能力](https://api-docs.deepseek.com/quick_start/pricing/)、[Qwen 模型目录](https://www.alibabacloud.com/help/en/model-studio/models)、[Gemini Flash](https://deepmind.google/models/gemini/flash/) 和 [Claude 模型目录](https://platform.claude.com/docs/en/models/overview) 选择。

只有新建用途配置首次选定可用 Key 时，才根据该 Key 的实际接口填入默认型号；接口不匹配或没有推荐时保持空白。已有配置加载、后续换 Key 和页面重绘都不应用推荐默认值，用户主动清空后也不重新填入。推荐不是可访问型号白名单，手填及已有型号即使未收录也保留；默认型号必须属于推荐列表，空列表的默认 ID 必须为空。

用户点击「模型」打开现有选择弹窗，内置推荐带默认标记与简短标签；未收录的当前型号仍可见。弹窗同时提供「手动填写」和「从服务获取」，只有后者联网。推荐不代表账户权限、地域可用性或实际联调通过，也不修改生成参数。更新推荐只影响新建配置，不覆盖已保存型号、不自动切换模型或发送测试请求。JSON 缺失或格式无效时仅停用内置推荐，手填及远端获取仍可用。

### 主动获取远端列表

用户在用途卡片编辑中主动操作触发 `GET {baseUrl}/models`，使用所选 Key 的 API 地址、协议及密钥鉴权。目录按接入点和凭证版本隔离，换 Key 后不能沿用另一账号的可访问模型结论。结果用于选择模型，不发回答请求；服务返回的能力信息和客户端适配器支持范围分别处理，不能替代实际用途测试。没有列表权限或服务不提供列表时可手工填写。

| 协议 | 请求分页参数 | 返回读取字段 | 后续页 |
| --- | --- | --- | --- |
| Responses / Chat | 无 | `data[].id` | 当前实现不追加分页 |
| Gemini | `pageSize=100`，后续带 `pageToken` | `models[].name`，移除一次 `models/` | `nextPageToken` |
| Claude | `limit=100`，后续带 `after_id` | `data[].id` | `has_more` 为 true 时读取 `last_id` |

最多读取 20 页；游标未推进、缺少必要分页字段、非列表格式或结果过多会报错。结果按 ID 去重并排序，不替换用户已经手工选择的模型。列表使用与回答相同的 HTTPS、鉴权、取消和超时规则。

## 6. 独立的 WAV 文件转写

录音首先写成本地 WAV，再由当前选用的音频转写卡片发送 OpenAI 兼容文件接口；之后按需把转写文字交给当前图文问答卡片处理。两者分别选择服务商、已有 API 密钥和模型，可共用也可分开；问答生成参数保存在问答配置中，转写提示和语言由全局提示词设置管理。仅原始转写不要求问答 Key，未配置转写不影响图文功能。Gemini／Claude 原生回答接口没有被当成音频文件转写接口，也不会自动把录音送入它们的原生音频能力。

转写提示和音频语言统一保存在全局提示词设置中，所有转写服务共用；服务配置不保存这两项，也不提供转写参数编辑区。模板可保存和应用全局转写提示、音频语言及输出方式；全局空值表示自动识别。兼容读取缺少全局语言字段的内部数据时，仅提取当前启用转写服务的语言并持久化；已保存的全局值优先，空值不从服务配置回填。

转写接入点必须声明本客户端已实现的 OpenAI 兼容文件转写支持，且目标服务实际实现 `POST {baseUrl}/audio/transcriptions`。新接入点须明确声明使用本客户端支持的文件转写接口；自定义接入点可由用户明确声明，但仍需主动验证。接入点的兼容声明不代表实际能力已经验证。Responses 或 Chat Completions 回答协议可选，不代表目标供应商提供转写路由。请求为非流式 `multipart/form-data`，Bearer 鉴权，字段如下：

```text
Content-Disposition: form-data; name="model"

YOUR_TRANSCRIPTION_MODEL_ID
Content-Disposition: form-data; name="response_format"

json
Content-Disposition: form-data; name="prompt"

可选的转写提示；为空时不发送此字段
Content-Disposition: form-data; name="file"; filename="audio.wav"
Content-Type: audio/wav

<WAV 文件字节>
```

实际请求包含自动生成的 multipart boundary 和各段分隔；上例仅展示字段。客户端按录音限制分段读取 WAV PCM，每段重建 WAV 头并上传，读取 JSON `text`，再拼接转写结果。转写模型、音频语言、输出格式能力由该接口决定；这里不实现 Realtime、原生 Gemini 音频、Claude 音频、语音合成或任意文件识别。[OpenAI 文件转写 API](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)

指定音频语言时，OpenAI 服务的 `gpt-transcribe` 按官方 multipart 格式发送 `languages[]`；其他模型和自定义兼容服务保持单值 `language`。自动识别时省略语言字段，不同时发送两种格式。转写提示与语言均进入请求快照及音频缓存标识；保存新值后再次提交音频会重新转写，历史追问继续复用已有原文。[OpenAI 转写上下文参数](https://developers.openai.com/api/docs/guides/speech-to-text#add-transcription-context)

实验室点读使用独立的朗读配置，不属于上述转写链路。OpenAI 新配置默认选择 `gpt-realtime-2.1-mini` 与 `marin`，可选 `gpt-realtime-2.1`；通过 Bearer 认证的 Realtime WebSocket 发送单次文字输入，收集 24 kHz 单声道 PCM，并在完整生成后保存为 WAV。不会打开麦克风或发送聊天历史；取消会关闭连接，完成后主动关闭会话，不自动重试。旧 `audio/speech` 模型与 MP3 文件继续兼容。模型目录使用当前 Key 的 `GET models`，仅用户主动发起；内置音色依据所选模型的官方列表展示，不通过模型目录推断音色。[Realtime 事件格式](https://developers.openai.com/api/reference/resources/realtime/client-events)、[音色列表](https://developers.openai.com/api/docs/guides/realtime-conversations)

## 7. 错误、凭据与配置变更

HTTP 3xx 明确提示填写最终地址；401／403 检查密钥、地域和权限；404 检查路由与模型；413 检查内容体积；429 检查限流／额度；5xx 和超时需用户手动重试。客户端不读取并回显任意 HTTP 错误正文。请求连接超时 20 秒、读超时 60 秒、整体上限 240 秒；取消令牌会主动断开连接。回答必须返回 SSE Content-Type，非流式 JSON 不会被误认为成功。

客户端不自动换供应商、协议、模型、版本，不自动关闭搜索／思考，不因错误重复发送收费请求。用户显式重试使用固定的请求配置快照；Claude pause_turn 的原生继续属于可见的独立操作。默认没有客户端工具执行循环；服务端搜索由供应商执行。

所有正文与事件均有限额：答案文字最多 256 Ki 字符，SSE 单行最多 1 Mi 字符、事件最多 2 Mi 字符、总输入最多 16 Mi 字符；超限报错。当前统一入口接受 PNG／JPEG／WebP Base64 data URL、总长度最多 16 Mi 字符；Gemini 适配器另限制为 12 Mi 字符。尽管适配器可解析更多原生格式，入口约束仍先执行。

API 密钥统一在管理页维护，问答与转写分别选用用途卡片。修改服务配置不清除历史记录、媒体、提示词与外观设置。内部实体与请求快照由 `ServiceCatalog` 管理。

密钥不进入公开配置 JSON，也不进入提示词预设。每个命名凭证独立保存由 Android Keystore 支持的 AES-GCM 密文；加密失败时保存失败，不回退明文。配置与密钥变更在同一次偏好提交中完成。普通配置字符串输出隐藏凭据，凭证版本用于使过期操作及能力缓存失效。密钥的服务及接入点范围限制必须匹配，不能因切换用途而把凭证送到另一站点。删除被引用的密钥或接入点会解除受影响的用途绑定，不自动选用其他服务；某个凭证无法解密不应使其他独立凭证不可读。

## 8. 维护入口

### 实验室回听字幕

回听字幕使用独立的 OpenAI Key 引用和翻译模型配置，不修改日常转写或问答卡片。仅接受绑定至 OpenAI 官方 HTTPS `/v1` 接口的 Key；设置、浏览字幕与切换显示语言不发起请求。

「音频提问同时生成字幕」默认关闭，需开启 Whisper 后使用。开启时，尚未识别的音频通过字幕任务取得完整原文，再交给当前问答模型；不要求日常转写卡片。字幕识别与中文翻译分属独立队列，问答不等待译文；翻译失败保留原文与检查点，不触发普通转写或自动重试。音频提问与手动制作共享同一录音的任务和检查点，取消待完成识别会中断该字幕任务，原文就绪后取消问答不取消翻译。原文缓存区分字幕与普通转写来源；未完成识别的重试检查原字幕配置与凭据，有原文的回答重试不再使用字幕凭据。此实验沿用字幕的英语识别方式，不采用日常转写卡片的模型、提示词或语言。

- Whisper 固定 `model=whisper-1`、`response_format=verbose_json`、`language=en`、`timestamp_granularities[]=segment`。录音超过上传大小限制时优先按静音分片，合并结果使用原始 PCM 位置换算绝对毫秒，保留静音间隔；不会从纯文本猜测时间戳。
- 中文翻译复用现有 Responses 或 Chat 流式请求，独立提示词要求按字幕编号返回 JSON；程序校验数量、编号及非空文本，时间轴不交给翻译模型修改。默认将整段英文字幕一次发送，输出上限为 128000 tokens；实验室可开启「分批翻译」，每批最多 16 条、约 3000 字符，输出上限为 8192 tokens。字幕翻译专用响应容量为 2 Mi 字符，总请求超时为 30 分钟，仍受所选模型自身限制。字幕成功片段与翻译结果保存检查点；手动继续只翻译缺失条目，整段模式携带已完成条目的原文作为上下文。不自动拆批、重试或切换模型。

字幕通过原子版本指针同时发布英文 SRT、中文 SRT 和元数据，新的双语结果成功前保留旧发布版本。元数据只保存内容摘要、模型与生成参数标识，不含 Key、原始配置或本机路径。删除录音或会话同时失效任务并清理字幕，避免迟到响应重新写回。Whisper 的弃用范围与期限见 [OpenAI 官方转写指南](https://developers.openai.com/api/docs/guides/speech-to-text)。

### 核心实现

核心代码位于 `apps/android/app/src/main/java/com/alwayside/android/`：`ServiceCatalog` 管理服务、密钥、用途配置及请求快照，`ProviderCapabilities` 执行已知能力限制，`ProviderProtocol` 校验通用选项与地址，`HttpTransport` 负责鉴权和网络边界，四种 Adapter 负责各自格式，`AiProvider.listModels` 负责模型列表，`SettingsStore`／`ServiceCatalogCodec` 负责配置存储，兼容格式读取代码仅用于识别内部历史数据，不自动建立服务绑定。

新增参数或扩展模型时，先检查官方协议及模型支持，再同步适配器、能力校验、设置界面与针对该分支的离线测试。测试应使用伪造 Map／SSE／HTTP 数据验证字段、完整性及失败分支。真实服务验证需独立记录日期、地域、具体模型和功能，不能从接口形状、模型名称或模型列表推断“已联调成功”。
