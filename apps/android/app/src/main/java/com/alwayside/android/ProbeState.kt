package com.alwayside.android

import android.os.Build

internal object ProbeState {
    private val serviceListeners = linkedSetOf<() -> Unit>()
    var serviceRunning = false
        set(value) {
            if (field == value) return
            field = value
            serviceListeners.toList().forEach { it() }
        }

    fun addServiceListener(listener: () -> Unit) { serviceListeners.add(listener) }
    fun removeServiceListener(listener: () -> Unit) { serviceListeners.remove(listener) }
    var sharing = false
    var recording = false
    var requesting = false
    var lastEvent = "尚未开启助手"
    fun diagnostics(): String = """
        AlwaySide ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        设备：${Build.MANUFACTURER} ${Build.MODEL}
        Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}
        固件：${Build.DISPLAY}
        悬浮服务：${if (serviceRunning) "已开启" else "已停止"}
        屏幕共享：${if (sharing) "进行中" else "未共享"}
        内部音频：${if (recording) "录制中" else "未录制"}
        AI 请求：${if (requesting) "进行中" else "空闲"}
        最近状态：$lastEvent
        仅在解释或发送时联网；诊断不包含密钥、问题、图片或录音。
    """.trimIndent()
}
