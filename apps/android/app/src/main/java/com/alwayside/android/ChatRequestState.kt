package com.alwayside.android

/** Request state is independent of whether the window currently contains visible messages. */
internal enum class ChatRequestPhase { READY, RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED, RESTORED }

internal data class RequestNotice(val summary: String, val detail: String, val isError: Boolean = true) {
    companion object {
        fun failure(error: Exception, beforeSubmission: Boolean = false): RequestNotice {
            val detail = when (error) {
                is java.net.SocketTimeoutException -> "请求超时；可手动重试"
                is java.net.UnknownHostException -> "无法连接服务器，请检查网络和 API 地址"
                is java.io.IOException -> "网络请求失败：${error.message.orEmpty().take(180)}"
                else -> error.message?.take(180)?.takeIf { it.isNotBlank() } ?: "请求失败，请检查配置后手动重试"
            }
            val summary = when (error) {
                is java.net.SocketTimeoutException -> "请求超时，可重试"
                is java.net.UnknownHostException, is java.net.ConnectException -> "连接失败，请检查网络和地址"
                is java.io.IOException -> "网络请求失败，可查看详情"
                is ProviderException -> error.summary
                else -> if (beforeSubmission) "发送失败，请检查配置" else "请求失败，请查看详情"
            }
            return RequestNotice(summary, detail)
        }
    }
}

internal fun floatingConversationStatus(kind: String, phase: ChatRequestPhase, status: String,
    awaitingAudioApproval: Boolean = false): String = when {
    awaitingAudioApproval -> "已达录音上限，录音已停止，尚未上传。请选择转写或丢弃。"
    phase == ChatRequestPhase.READY -> "${when (kind) { "photo" -> "截图"; "audio" -> "录音"; else -> "原文" }}已就绪，输入问题后发送"
    else -> status
}
