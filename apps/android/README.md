# AlwaySide Android

原生 Kotlin Android 客户端。当前为设备能力测试版，无联网权限、不调用模型。最低 Android 10（API 29）。

## 构建

需要 JDK 17、Android SDK Platform 36 与 Build-Tools 36.0.0。项目固定 AGP 9.5.0 / Gradle 9.6.0。为该进程设置 JAVA_HOME、ANDROID_HOME、GRADLE_USER_HOME，然后在本目录运行 Gradle Wrapper。

调试签名仅用于本地测试，私钥不入库；覆盖升级需要使用同一签名。

开发计划、设备测试结果及交付记录在根 `.local/android/`，不纳入 Git。

## 使用

主界面开启悬浮球并允许系统悬浮权限；共享屏幕后返回目标应用。点击球打开面板，长按进入冻结画面框选，拖动可移动并贴边。面板可以取整屏预览、框选和输入草稿；本包没有 AI 提交功能。

内部音频需要另行授予录音权限，数据源仅使用 AudioPlaybackCapture。面板开启录制，收起后小球变色，再点击小球结束；也可从面板结束。每段最多两分钟，可以回听或丢弃。来源应用拒绝采集时可能无声，不切换麦克风。图片不落盘；录音只在私有缓存，停止助手或重新启动服务时清理。

构建与静态检查：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。产物为 `app/build/outputs/apk/debug/app-debug.apk`。单元测试覆盖裁剪坐标边界与 PCM WAV 封装，窗口行为和音源可录性需真机确认。
