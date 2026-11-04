# AlwaySide Android

原生 Kotlin Android 客户端。当前为设备能力测试版，无联网权限、不调用模型。最低 Android 10（API 29）。

## 构建

需要 JDK 17、Android SDK Platform 36 与 Build-Tools 36.0.0。项目固定 AGP 9.5.0 / Gradle 9.6.0。为该进程设置 JAVA_HOME、ANDROID_HOME、GRADLE_USER_HOME，然后在本目录运行 Gradle Wrapper。

调试签名仅用于本地测试，私钥不入库；覆盖升级需要使用同一签名。

开发计划、设备测试结果及交付记录在根 `.local/android/`，不纳入 Git。
