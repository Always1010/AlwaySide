# 文档图片与动图工具

手动运行的 Android 原生组件演示工程。用于界面调整后更新文档素材，不参与 App 构建、提交钩子或 CI，也不会自动覆盖 README、使用说明及其图片。

## 视觉基准与最小改动

更新素材先确定本次变化：修改回答内容、同步实际界面，或新增功能演示。仅修改对应部分；更换采集或编码方式不能连带重新设计背景、排版和装饰。尚未审核的生成稿不能自动成为下一轮的视觉基准。

| 范围 | 维护规则 |
| --- | --- |
| 外框与说明 | 同一套素材共用外框、标题、圆角、配色、图注及手势提示样式。局部修图不修改公共外框；整套改版需作为明确的独立需求。 |
| 示例背景 | 沿用相同场景、标题、素材与信息层级。功能更新不更换阅读材料或音频来源，不增加装饰或提示文案。 |
| 应用界面 | 同步当前真实组件与必要的操作变化；窗口、消息及字幕的排版由对应组件决定，不为展示而改动 App。 |
| 回答内容 | 默认效果遵循内置提示词；自定义拓展用途与默认效果区分。内容变长时使用滚动，不挤压背景或缩小字号。 |
| 新增场景 | 使用同一公共外框及现有页面样式；只增加演示该功能所需的内容与动作。 |

音频背景以[原版本参考](references/audio-background.png)为基准，来源和裁切位置见[参考记录](references/audio-background.json)。参考仅限定外部播放器背景，不限定 App 内回放或字幕界面的功能布局。

- 保留 `LISTENING NOTE`、`Team planning`、`Next two weeks`、`Meeting recording · 01:18` 的顺序与留白。标题 29sp、说明 17sp、分类 11sp，均为普通字重；不因复用 Material 标题样式而加粗辅助文字。
- 播放器沿用约 58dp 高、14dp 圆角的单行浅灰容器：左侧播放或暂停图标，中间蓝色进度线与圆点，右侧总时长。播放状态通过图标与进度表达，不另加波形、状态文字或两行时间区。
- 录音提示、转写结果与播放器为不同层次。结果窗出现时保留播放器主要控件，不通过重新设计背景来避让窗口。

整套素材以[原版本记录](references/teaching-style.json)为视觉基准，使用 `original-teaching` 演示样式，保留原版品牌区、状态栏和图注层次；动图按场景显示步骤编号与手势提示，由 `teaching_presentation.py` 绘制教学标注，小窗仍使用当前原生组件。

每个场景只表达一个明确的操作目标，以当前已确认素材的展示范围和视觉为准。压缩时长不能省略必要动作；调整说明或节奏时，先核对实际采用的场景配置与原生帧来源，不能用过期配置覆盖已经确认的版本。

每次导出前，在同一尺寸下对照原素材与新素材，检查空闲、操作中、结果和末尾四种状态。核对本次目标以外的字体、字重、位置、背景及提示是否发生变化；无关变化应恢复。只重采和导出受影响的场景。

## 工程边界

```text
apps/android/app/src/main/  ──只读──→  android-preview/build/generated/
                                          ↓
scenarios/showcase.json  ───────────→  独立演示 APK
                                          ↓ 模拟器原生 View 逐帧采集
.local/doc-media/  ←─────────────  PNG 帧、布局坐标、来源校验值
        ↓ Pillow 后期：手势提示、说明文字、GIF 编码
本地预览 → 人工确认 → 显式 export 选中的 PNG/GIF
```

- `android-preview/` 有自己的 Gradle settings、构建脚本、Manifest 和包名 `com.alwayside.docmedia`。它不安装或启动正式 App。
- 构建时单向读取 App 的悬浮菜单、小窗、主题与文字操作组件，以及 `AudioPlaybackIcon`、`SubtitlePanel` 和字幕显示依赖。完整清单在 Gradle 的 `StagePresentation` 与 Python 的 `SHARED` 中同步维护；所有共享源码、图标、主题和文字操作 ID XML 均参与 APK 来源校验。生成目录只包含源码和资源的临时副本，不维护另一套菜单、小窗或字幕面板。
- App 的 Gradle 配置没有包含演示工程。删除整个 `tools/doc_media/`，App 仍可独立构建和运行。工具复用 App 的 Gradle Wrapper 作为固定版本的启动器，`-p` 始终选择演示工程；也可传入现有的 `--gradle`。
- 演示 Activity 在计时前预热共享组件，等待布局完成后采集；负责承载布局、预设回答和动作时序；没有网络、麦克风、屏幕采集和系统悬浮窗权限。不会调用模型、读取用户会话或录制真实音频。
- 音频背景保留会议播放器、播放状态、时间与进度。回听演示以预设本地状态驱动真实播放图标、字幕面板和字幕浮窗；字幕使用已保存的示例时间轴，禁用制作入口，不模拟 API 成功。完整字幕弹窗按实际屏幕位置合成进采集帧；字幕浮窗在演示舞台内承载正式组件，不申请系统悬浮权限。
- 图片里的菜单、小窗、按钮、悬浮球和滚动由原生组件渲染；菜单承载层补齐与 App 无模糊回退一致的 32% 遮罩。外层标题、说明和手势圈在采集后叠加，标记位置取自原生 View 的实时边界。此工程演示手势含义，不验收正式悬浮服务的手势识别与系统授权流程。
- 演示舞台默认 400 × 800dp，小窗沿用 App 的 320dp 宽度与默认高度计算，展开输入区不额外放大窗口；输出按 432px 逻辑宽度的统一圆角画框排版；以两倍分辨率合成，PNG 保留 864px 宽，GIF 缩至 540px 宽，与原正式素材的分辨率一致。动图按 50ms 固定步进驱动预设动作，每一步均由原生 View 渲染，导出固定 20fps；布局和 PNG 写入完成后再推进演示时间，设备耗时仅影响生成速度。PNG 压缩交给两个后台线程，最多保留三帧待写。渲染器校验完整时间步与最终 GIF 的每帧时长，连续点击须逐次显示按下和松开。

后续修改共享组件、图标、配色后，**需要时手动重新 build → capture → render** 即可带入界面变化。APK 来源校验会拒绝过期包。如果组件接口变化、页面拆分或新增依赖导致编译失败，应更新演示承载代码或复用清单；工具不会自动猜测新接口。当前不涵盖「AI 服务」「提示词」「实验室」等整页界面，这些页面需要单独采集真实界面。

## 文件与场景

| 位置 | 维护内容 |
| --- | --- |
| `android-preview/build.gradle.kts` | 单向复用清单、Android 构建配置 |
| `android-preview/src/main/kotlin/com/alwayside/android/DocMediaActivity.kt` | 原生场景承载、事件调度、帧采集、View 坐标 |
| `android-preview/src/main/kotlin/com/alwayside/android/DocReplayScenes.kt` | 回听聊天视图、真实字幕面板、弹窗画面采集 |
| `android-preview/src/main/kotlin/com/alwayside/android/DocSubtitleOverlay.kt` | 在私有演示舞台承载正式字幕浮窗，固定示例主题与透明度 |
| `android-preview/src/main/kotlin/com/alwayside/android/DocReplayFixtures.kt` | 预设本地播放状态和字幕数据，隔离实际存储与网络 |
| `scenarios/showcase.json` | 已确认的阅读/音频文本、追问回答、动作时序和说明 |
| `doc_media.py` | 环境检查、构建、模拟器采集、后期渲染和显式导出 |
| `teaching_presentation.py` | 原版公共画框、步骤编号、手势气泡及分层图注 |
| `requirements.txt` | 后期依赖；只需 Pillow，不需要 FFmpeg |

| 场景 ID | 导出格式 | 内容 |
| --- | --- | --- |
| `S01-screenshot-medium` | PNG | 截图后分段对照原文与译文 |
| `S02-followup-result` | PNG | I'm down 追问与完整回答 |
| `S04-audio-original` | PNG | 会议录音的转写原文，保留播放器背景 |
| `S05-audio-translation` | PNG | 同一会议的分段双语回答 |
| `S06-floating-menu` | PNG | 当前原生单击菜单；只保留一张菜单图 |
| `A01-long-press-followup` | GIF | 长按截图 → 分段对照 → 输入并发送 I'm down 追问 → 完整回答 |
| `A02-double-tap-audio` | GIF | 播放器播放 → 双击录音 → 结束 → 转写原文 → 分段双语回答与滚动 |
| `A04-triple-tap-cancel` | GIF | 录音中 → 三击取消 → 恢复空闲 |
| `A05-audio-playback` | GIF | 3.5 秒：在已开启「音频播放时显示字幕」时，点击播放，字幕浮窗自动出现 |
| `A06-audio-subtitles` | GIF | 回听已有字幕 → 跟随播放 → 切换中文与双语 → 点击当前字幕，从 00:06 句首重听 → 暂停 |

追问保留已确认的“I'm down为什么这里是我可以，而不是心情低落？”。音频沿用会议录音背景与内容，回放和字幕使用同一会议的预设资料。A05 使用场景内的 `autoSubtitles` 与 `replayStartMs`，从预设音频的 5.5 秒处演示播放后自动出现的黑色字幕浮窗，透明度为 85%；不包含暂停、继续流程。外语默认回答按原文在上、译文在下分段对照，不使用表格；摘要等自定义拓展场景通过文档文字介绍。动作时长定义在 JSON 中，以步骤完整、信息必要、节奏干练为准；不为压秒数省略必要动作，也不因取消时长上限增加无意义的停留、重复或滚动。演示时间写入 GIF，实际采集耗时单独记录在 manifest 中。

所有结果继续复用正式 `AssistantWindowViews`、`AssistantMenuViews` 和 `assistantMessageRow`。截图来源槽放置由原生示例页面绘制的预览，默认收起；场景只负责内容和动作。

仓库仅维护工具和必要的文本场景配置；不导入旧预览、历史生成脚本或未采用的素材。已发布的 `docs/images/` 保持原位。所有新帧、联系表、最终帧预览、来源记录和候选图片默认输出到忽略的 `.local/doc-media/`，只有明确导出的 PNG/GIF 才进入选定素材目录。

## 环境

- JDK 17、SDK Platform 36、Build-Tools 36.0.0；Gradle 和 Material Components 版本与当前 App 一致。
- Python 3.10+；后期使用 `requirements.txt` 中的 Pillow。
- 一个已启动的 Android 模拟器。建议 API 36、864 × 1728 px、320 dpi、字体比例 1、浅色、中文系统环境；其他参数会改变换行和画面高度。演示 APK 最低 API 30。
- 后期说明文字需已安装的中文字体；Windows 默认用微软雅黑，Linux/macOS 尝试 Noto Sans CJK/PingFang，也可用 `render --font` 指定。App 内文字始终使用 Android 原生字体。

工具不会安装 SDK、Python 依赖或字体，也不会修改 PATH/系统设置。首次准备环境时，应先取得本机环境变更授权，再使用 Android Studio/SDK Manager 或自己管理的虚拟环境配置。按本仓库协作约定，建议把专用 SDK、AVD 和缓存放在 `.local/doc-media/` 下；已有 SDK 可以直接复用。

下面命令均在仓库根目录运行。`--sdk` 与 `--java-home` 应替换为本机已有目录，或使用现有 `ANDROID_HOME`、`JAVA_HOME`。路径选项只传给子进程，不写入系统环境。

```powershell
python tools/doc_media/doc_media.py doctor --sdk .local/android-sdk

python tools/doc_media/doc_media.py build --offline `
  --sdk .local/android-sdk `
  --java-home D:/path/to/jdk-17 `
  --gradle-user-home .local/gradle-cache `
  --android-user-home .local/doc-media/android-user
```

`--offline` 要求所需依赖已存在于指定 Gradle 缓存中；缺失时会失败。需要下载依赖时，取得相应授权后去掉此选项。默认 Android 调试签名可能在 Android 用户目录创建 debug keystore；可通过 `--android-user-home` 把该目录放在项目本地，或通过 `--keystore` 使用已有 `android/android` 密码、`alwayside-test` 别名的测试密钥。密钥不入库，不使用正式发布签名。

## 手动采集与预览

先启动专用模拟器；可以用 `-no-window -no-audio -no-snapshot` 无窗口运行。确认序列号：

```powershell
.local/android-sdk/platform-tools/adb.exe devices -l
```

工具要求显式的 `emulator-数字` 序列号并核实模拟器属性，拒绝真机。它只安装独立演示 APK，在其私有目录写入帧并拉取到本地；采集完成后清理本次私有帧目录并停止演示进程，不更改系统动画、屏幕尺寸或正式 App 数据。

```powershell
python tools/doc_media/doc_media.py capture --sdk .local/android-sdk `
  --serial emulator-5580 --scene S06-floating-menu `
  --output .local/doc-media/menu-capture

python tools/doc_media/doc_media.py render `
  --input .local/doc-media/menu-capture
```

省略 `--scene` 采集全部十个场景；重复传入该选项可选多个。`--output` 目录必须不存在，防止混入上次帧。每次采集前会比较 APK、共享源码、主题图标、场景和承载代码的 SHA-256；有变化就提示重新构建。

`capture` 未指定输出时，写入 `.local/doc-media/captures/时间戳/`；`render` 默认写入输入目录旁的 `目录名-rendered/`。动图同时生成 `-contact.png` 联系表和 `-final.png` 最终帧，便于检查追问回答、换行与滚动终点。这两类检查图不属于正式导出资产。

检查重点：菜单与当前 App 一致；追问发送后确实出现完整回答；长原文与译文滚动可读；说明和点击圈没有遮住关键内容；循环末尾留有阅读时间。模拟器速度会影响原生动画与帧间隔，因此不承诺不同机器逐字节相同。`manifest.json` 记录实际帧时间、尺寸、API、密度和控件坐标，`provenance.json` 记录 APK/输入文件校验值。

## 人工确认后的导出

先在本地看图，再只导出选中的场景。以下命令会写入指定目录；已有文件必须额外传 `--replace` 才能替换：

```powershell
python tools/doc_media/doc_media.py export `
  --input .local/doc-media/menu-capture-rendered `
  --scene S06-floating-menu --dest docs/images --replace
```

工具不会替你修改 Markdown、创建 commit 或 push。导出后按文档需要调整引用，并遵守项目提交规则。同一文档内避免重复放已被动图覆盖的静态内容；README 与使用说明可以复用同一正式资产。

## 维护验证

改共享组件后，构建并采集受影响场景即可；只改 Python 中后期说明的样式、手势标记的绘制方式，可以对已有原生帧重新 render。修改 JSON 中的文案或时序需要重新 build 和 capture。脚本边界检查可运行：

```powershell
python -m unittest discover -s tools/doc_media/tests
```

真实悬浮权限、录音采集和模型响应仍由 App 自己的验证流程负责。此工具只对预设内容和共享组件的文档展示负责。

未采用的设置素材 `S07-api-connection.png`、`S08-model-settings.png` 不用于文档展示；现有共享组件演示不包含这些设置页。
