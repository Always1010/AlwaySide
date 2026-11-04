# AlwaySide 浏览器扩展

AlwaySide 是无需构建、无第三方运行依赖的 Manifest V3 侧栏扩展，优先支持 Microsoft Edge，并兼容具有 Side Panel API 的 Chrome（最低 Chromium 116）。本轮实现独立浏览器原型，尚未承诺与 iPad 端功能完全一致。

## 加载与使用

1. 在 Edge 打开 `edge://extensions`（Chrome 为 `chrome://extensions`），启用开发人员模式。
2. 选择“加载解压缩的扩展”，选择本 README 所在的 `apps/browser-extension` 文件夹。无需安装依赖或运行构建命令。
3. 打开普通 HTTP / HTTPS 网页，将 AlwaySide 固定到工具栏，并点击扩展按钮。这个点击同时打开侧栏、临时授权扩展访问当前网页。
4. 在侧栏设置中填写自己的 OpenAI API Key 和模型配置。保存设置不联网。当前仅支持 OpenAI Responses API，不支持自定义服务地址。
5. 点击选中文字、网页正文或可见区域截图进行取材；确认预览、来源和采集时间后，填写问题并发送。取材本身不会上传，只有发送才调用 API。可以继续追问、停止回答、新建对话、恢复或删除本机历史。

切换标签页或跨站导航后，请在新的目标页面再次点击工具栏的 AlwaySide 按钮授权。仅在侧栏内点击取材按钮不能授予新的 `activeTab` 权限。从浏览器自带侧栏菜单直接打开 AlwaySide，也可能需要先点击工具栏授权。

## 采集范围

- 只读取明确操作时活动标签的顶层页面。选区只支持普通正文选区，不读取输入框、密码框或跨域 iframe 内的选区；整页资料使用页面可见正文文本，不包含完整 HTML。
- 文字最多 30,000 字符，超出显示截断提示。截图仅当前可见网页区域，不是滚动长图；最大边缩至 1,600 像素并压缩为 JPEG，过大的截图拒绝采集。
- 浏览器内部页、扩展页、本地文件、内置 PDF 阅读器、扩展商店等受保护页面不支持采集。动态加载页面需等内容显示后重新取材。
- 每份资料固定来源 URL、标题和采集时间。采集前后核对标签及 URL；采集过程中切换标签或跳转会丢弃结果。页面原地动态更新不提供文档快照保证，预览以当次实际采集内容为准。
- 取材失败不会自动改用整页或旧截图；不会后台自动抓取、自动上传、重试或切换模型。

## 权限与数据

扩展只申请 `sidePanel`、`activeTab`、`scripting` 和 `storage`；网络主机权限只有 `https://api.openai.com/*`。不申请所有网站、`tabs`、剪贴板、麦克风或无限存储权限，不加载远程脚本，不注入常驻内容脚本。

API Key 仅存放于扩展的 `chrome.storage.session` 内存会话区，限制为受信任扩展上下文访问；不会写入 `storage.local`、`storage.sync`、对话历史或注入网页的脚本。浏览器重启、扩展重载、更新或禁用后需重新填写。密钥只用于你点击发送后向 OpenAI 发出的授权请求。

设置、网页资料（包括截图和原始来源 URL）和对话历史保存在当前浏览器的 `storage.local`，没有云同步，也没有额外加密。不要在共享浏览器配置中保存敏感资料。删除历史会删除相应本地记录；卸载扩展会清除其扩展存储。历史最多 30 个对话、每个 60 轮，不自动删除最旧记录。浏览器存储通常有 10 MB 限额，大截图可能较快用尽；保存失败会显示错误并保留当前侧栏内容，需删除不需要的历史后重试。多个窗口同时操作历史时使用扩展内的互斥锁和快照冲突检查；历史被其他窗口修改后，本窗口保存会明确报错，需重新加载历史再保存。

发送给 OpenAI 的内容包含当前固定资料、问题、配置的指令以及最近至多 3 轮完整问答；来源 URL 完全不作为来源元数据发送，仅在本机显示和保存。若页面正文或标题自身包含敏感地址或其他信息，它们仍可能包含在资料里，请先检查预览。启用联网搜索时，模型可使用 OpenAI 的搜索工具；具体收费和处理规则由你的 OpenAI 账户决定。

每次回答最多保留 12,000 个可见文字字符；超过此上限或模型输出预算耗尽时，会标记为不完整。未完成、取消或搜索要求未满足的回答不会作为完整问答带入后续请求。停止请求后会保留已收到的部分内容，不会自动重试。

## 验证

已有 Node.js 时，在本文件夹运行定向测试：

```powershell
node --test tests/browser.test.mjs
node --test tests/core.test.mjs
```

测试使用 Node 内置测试框架、模拟浏览器 API 和模拟模型响应，不使用真实 API Key、不调用付费 API。浏览器单元测试覆盖最小权限、固定来源、切换标签/跳转丢弃结果、空选区、截图缩放、密钥会话隔离和存储错误。它们不能替代 Edge / Chrome 中的真实侧栏交互和页面兼容性验收。

API 行为参考官方文档：[Edge 侧栏扩展](https://learn.microsoft.com/en-us/microsoft-edge/extensions/developer-guide/sidebar)、[Chrome Side Panel](https://developer.chrome.com/docs/extensions/reference/api/sidePanel)、[activeTab](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab)、[截图与标签权限](https://developer.chrome.com/docs/extensions/reference/api/tabs)、[会话与本地存储](https://developer.chrome.com/docs/extensions/reference/api/storage)。
