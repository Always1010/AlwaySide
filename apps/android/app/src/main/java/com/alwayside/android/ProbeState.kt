package com.alwayside.android

import android.os.Build

internal object ProbeState {
    var serviceRunning = false
    var sharing = false
    var recording = false
    var lastEvent = "尚未开启助手"
    fun diagnostics(): String = """
        AlwaySide ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        设备：${Build.MANUFACTURER} ${Build.MODEL}
        Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}
        固件：${Build.DISPLAY}
        悬浮服务：${if (serviceRunning) "已开启" else "已停止"}
        屏幕共享：${if (sharing) "进行中" else "未共享"}
        内部音频：${if (recording) "录制中" else "未录制"}
        最近状态：$lastEvent
        此版本无联网权限，不发送图片或声音。
    """.trimIndent()
}
